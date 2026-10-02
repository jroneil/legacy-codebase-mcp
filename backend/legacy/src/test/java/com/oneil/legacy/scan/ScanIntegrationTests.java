package com.oneil.legacy.scan;

import static com.oneil.legacy.scan.ScanModel.*;
import static org.assertj.core.api.Assertions.*;

import com.oneil.legacy.PostgresTestSupport;
import com.oneil.legacy.framework.FrameworkIndexer;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScanIntegrationTests extends PostgresTestSupport {
    @TempDir Path root;
    private Path repositoryRoot;
    @Autowired ScanService service;
    @Autowired ScanStore store;
    @Autowired ScanProperties properties;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void fixture() throws Exception {
        // Reset only the disposable Testcontainers database, never the user's database.
        jdbc.execute("TRUNCATE java_relationship, java_symbol, source_file, analysis_error, active_scan, scan");
        jdbc.update("INSERT INTO active_scan (singleton) VALUES (true)");
        repositoryRoot = root.resolve("repo-a");
        Files.createDirectories(repositoryRoot);
        Path source = Path.of(getClass().getResource("/fixtures/inventory").toURI());
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = repositoryRoot.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination);
            }
        }
        properties.setRepositoryBase(root.toString());
        properties.setAnalyzerVersion("fixture-analyzer-1");
    }

    @Test
    void migrationAppliesAndValidatesWithoutHibernateSchemaCreation() {
        flyway.validate();
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("4");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(4);
        assertThat(store.list(100, 0).activeScanId()).isNull();
        assertThatThrownBy(() -> store.active(100, 0)).isInstanceOf(ScanStore.ScanNotFoundException.class);
    }

    @Test
    void completesRepeatedSnapshotsWithoutDuplicatesAndRemovesDeletedFiles() throws Exception {
        ScanDetail first = service.scan("repo-a");
        ScanDetail second = service.scan("repo-a");
        assertThat(first.scan().status()).isEqualTo(Status.COMPLETED);
        assertThat(second.scan().id()).isNotEqualTo(first.scan().id());
        assertThat(second.files().items()).isEqualTo(first.files().items()).hasSize(3);
        assertThat(second.scan().analyzerVersion()).isEqualTo("fixture-analyzer-1");
        assertThat(second.scan().startedAt()).isAfterOrEqualTo(second.scan().createdAt());
        assertThat(second.scan().completedAt()).isAfterOrEqualTo(second.scan().startedAt());
        assertThat(second.active()).isTrue();
        assertThat(store.detail(first.scan().id(), 100, 0).active()).isFalse();
        Files.delete(repositoryRoot.resolve("src/Example.java"));
        ScanDetail third = service.scan("repo-a");
        assertThat(store.active(100, 0).scan().id()).isEqualTo(third.scan().id());
        assertThat(third.files().items()).extracting(SourceFile::relativePath).containsExactly("broken.xml", "config/application.properties");
        assertThat(store.detail(first.scan().id(), 100, 0).files().items()).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_file", Integer.class)).isEqualTo(8);
    }

    @Test
    void invalidRepositoryIsRejectedBeforeCreatingAScan() {
        ScanDetail good = service.scan("repo-a");
        assertThatThrownBy(() -> service.scan("missing"))
                .isInstanceOf(RepositoryCatalog.RepositorySelectionException.class);
        assertThat(store.list(100, 0).scans().totalCount()).isEqualTo(1);
        assertThat(store.active(100, 0).scan().id()).isEqualTo(good.scan().id());
    }

    @Test
    void scansTwoSelectedRepositoriesAsDistinctImmutableSnapshots() throws Exception {
        Path secondRoot = root.resolve("repo-b");
        Files.createDirectories(secondRoot);
        Files.writeString(secondRoot.resolve("OnlyInB.java"), "class OnlyInB {}");

        var first = service.scan("repo-a");
        var second = service.scan("repo-b");

        assertThat(first.scan().repositoryRoot()).isEqualTo(repositoryRoot.toString());
        assertThat(second.scan().repositoryRoot()).isEqualTo(secondRoot.toString());
        assertThat(first.scan().id()).isNotEqualTo(second.scan().id());
        assertThat(second.files().items()).extracting(SourceFile::relativePath).containsExactly("OnlyInB.java");
        assertThat(store.detail(first.scan().id(), 100, 0).files().items()).hasSize(3);
        assertThat(store.active(100, 0).scan().id()).isEqualTo(second.scan().id());
    }

    @Test
    void fileErrorsAndHashesArePersistedWithoutFailingTheSnapshot() throws Exception {
        Files.write(repositoryRoot.resolve("invalid.properties"), new byte[] {(byte) 0xc3, 0x28});
        var result = service.scan("repo-a");
        assertThat(result.scan().status()).isEqualTo(Status.COMPLETED);
        assertThat(result.scan().fileCount()).isEqualTo(4);
        assertThat(result.scan().errorCount()).isEqualTo(1);
        assertThat(store.active(100, 0).errors().items()).singleElement().satisfies(error -> {
            assertThat(error.relativePath()).isEqualTo("invalid.properties");
            assertThat(error.resolutionState()).isEqualTo("UNRESOLVED");
        });
        assertThat(result.files().items()).allSatisfy(file -> assertThat(file.contentHash()).matches("[a-f0-9]{64}"));
    }

    @Test
    void unreadableSourceIsRecordedAndOtherFilesComplete() throws Exception {
        Path unreadable = repositoryRoot.resolve("unreadable.java");
        Files.writeString(unreadable, "private source");
        Files.setPosixFilePermissions(unreadable, java.util.Set.of());
        try {
            var result = service.scan("repo-a");
            assertThat(result.scan().status()).isEqualTo(Status.COMPLETED);
            assertThat(result.scan().fileCount()).isEqualTo(3);
            assertThat(result.errors().items()).singleElement().satisfies(error -> {
                assertThat(error.relativePath()).isEqualTo("unreadable.java");
                assertThat(error.code()).isEqualTo("READ_FAILED");
            });
        } finally {
            Files.setPosixFilePermissions(unreadable, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    void persistenceFailureRollsBackEntireInventoryAndPreservesActive() {
        var first = service.scan("repo-a");
        assertThatCode(() -> Files.createDirectory(root.resolve("repo-b"))).doesNotThrowAnyException();
        var file = first.files().items().getFirst();
        var brokenInventory = new RepositoryInventory() {
            @Override public Inventory collect(Path path) { return new Inventory(List.of(file, file), List.of(), null); }
        };
        var failure = new ScanService(store, brokenInventory, properties, new RepositoryCatalog(properties), new com.oneil.legacy.symbol.JavaSymbolIndexer(), new com.oneil.legacy.grails.GrailsIndexer(), new FrameworkIndexer(), new com.oneil.legacy.database.DatabaseIndexer()).scan("repo-b");
        assertThat(failure.scan().status()).isEqualTo(Status.FAILED);
        assertThat(failure.files().items()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_file WHERE scan_id=?", Integer.class, failure.scan().id())).isZero();
        assertThat(store.active(100, 0).scan().id()).isEqualTo(first.scan().id());
    }

    @Test
    void pendingAndRunningAreOperationalMetadataOnly() {
        UUID id = store.create(repositoryRoot.toString(), "fixture");
        var pending = store.detail(id, 100, 0);
        assertThat(pending.scan().status()).isEqualTo(Status.PENDING);
        assertThat(pending.files().items()).isEmpty();
        assertThat(pending.active()).isFalse();
        store.start(id);
        // Even accidentally committed staging rows cannot leak through query services.
        jdbc.update("""
                INSERT INTO source_file VALUES (?, 'staging.java', 'JAVA', ?, 'UTF-8', 0)
                """, id, "0".repeat(64));
        assertThat(store.detail(id, 100, 0).files().items()).isEmpty();
        assertThat(store.list(100, 0).activeScanId()).isNull();
    }

    @Test
    void readersSeeOldSnapshotUntilPublicationCommits() throws Exception {
        var old = service.scan("repo-a");
        UUID id = store.create(repositoryRoot.toString(), "replacement");
        store.start(id);
        var collected = new RepositoryInventory().collect(repositoryRoot);
        var publishedInsideTransaction = new CountDownLatch(1);
        var allowCommit = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> writer = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                store.complete(id, collected);
                publishedInsideTransaction.countDown();
                await(allowCommit);
            }));
            try {
                assertThat(publishedInsideTransaction.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(store.active(100, 0).scan().id()).isEqualTo(old.scan().id());
                assertThat(store.detail(id, 100, 0).scan().status()).isEqualTo(Status.RUNNING);
                assertThat(store.detail(id, 100, 0).files().items()).isEmpty();
                var response = get("/api/scans/" + id);
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(json(response).path("files").path("items").size()).isZero();
            } finally { allowCommit.countDown(); }
            writer.get(10, TimeUnit.SECONDS);
        }
        assertThat(store.active(100, 0).scan().id()).isEqualTo(id);
        assertThat(store.active(100, 0).files().items()).hasSize(3);
    }

    @Test
    void concurrentPublicationsSerializeWithoutMixingSnapshots() throws Exception {
        UUID first = store.create(repositoryRoot.toString(), "first");
        UUID second = store.create(repositoryRoot.toString(), "second");
        store.start(first);
        store.start(second);
        var inventory = new RepositoryInventory().collect(repositoryRoot);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> one = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                store.complete(first, inventory);
                locked.countDown();
                await(release);
            }));
            Future<?> two = null;
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                two = executor.submit(() -> { secondStarted.countDown(); store.complete(second, inventory); });
                assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
                Future<?> waiting = two;
                assertThatThrownBy(() -> waiting.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                assertThat(store.list(100, 0).activeScanId()).isNull();
            } finally { release.countDown(); }
            one.get(10, TimeUnit.SECONDS);
            if (two != null) two.get(10, TimeUnit.SECONDS);
        }
        assertThat(store.active(100, 0).scan().id()).isEqualTo(second);
        assertThat(store.detail(first, 100, 0).files().items()).isEqualTo(store.detail(second, 100, 0).files().items());
    }

    @Test
    void databaseRejectsInvalidTransitionsAndTerminalSnapshotChanges() {
        UUID pending = store.create(repositoryRoot.toString(), "fixture");
        assertThatThrownBy(() -> jdbc.update("UPDATE active_scan SET scan_id=?", pending)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE scan SET status='COMPLETED', started_at=now(), completed_at=now() WHERE id=?", pending))
                .isInstanceOf(DataAccessException.class);
        var completed = service.scan("repo-a");
        UUID id = completed.scan().id();
        assertThatThrownBy(() -> jdbc.update("UPDATE scan SET analyzer_version='changed' WHERE id=?", id)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM scan WHERE id=?", id)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE source_file SET encoding='changed' WHERE scan_id=?", id)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM source_file WHERE scan_id=?", id)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO source_file VALUES (?, 'new.java', 'JAVA', ?, 'UTF-8', 0)", id, "0".repeat(64)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void gitCommitIsCollectedFromRealRepositoryAndTargetIsUntouched() throws Exception {
        git("init", "--quiet");
        git("add", ".");
        git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "fixture");
        String sha = git("rev-parse", "HEAD").trim();
        assertThat(git("status", "--porcelain")).isEmpty();
        var scan = service.scan("repo-a");
        assertThat(scan.scan().gitCommitSha()).isEqualTo(sha);
        assertThat(git("status", "--porcelain")).isEmpty();
        assertThat(scan.files().items()).noneMatch(file -> file.relativePath().startsWith(".git/"));
    }

    @Test
    void restEndpointsExposeBoundedInventoryMetadataAndUsefulErrors() throws Exception {
        Files.createDirectories(root.resolve("repo-b"));
        Files.createDirectories(root.resolve(".hidden"));
        var before = get("/api/scans");
        assertThat(before.statusCode()).isEqualTo(200);
        assertThat(json(before).path("activeScanId").isNull()).isTrue();
        var repositories = json(get("/api/repositories"));
        assertThat(repositories.path("items").path(0).path("id").asText()).isEqualTo("repo-a");
        assertThat(repositories.path("items").path(1).path("id").asText()).isEqualTo("repo-b");
        assertThat(repositories.path("totalCount").asInt()).isEqualTo(2);
        assertThat(repositories.path("truncated").asBoolean()).isFalse();
        var created = post("repo-a");
        assertThat(created.statusCode()).isEqualTo(201);
        String id = json(created).path("scan").path("id").asText();
        assertThat(created.headers().firstValue("Location")).contains("/api/scans/" + id);
        var detail = json(get("/api/scans/" + id + "?limit=1"));
        assertThat(detail.path("files").path("items").size()).isEqualTo(1);
        assertThat(detail.path("files").path("totalCount").asInt()).isEqualTo(3);
        assertThat(detail.path("files").path("truncated").asBoolean()).isTrue();
        assertThat(json(get("/api/scans/" + id + "?limit=1&offset=2")).path("files").path("truncated").asBoolean()).isFalse();
        assertThat(json(get("/api/scans")).path("activeScanId").asText()).isEqualTo(id);
        assertThat(get("/api/scans/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
        assertThat(get("/api/scans/not-a-uuid").statusCode()).isEqualTo(400);
        assertThat(get("/api/scans?limit=201").statusCode()).isEqualTo(400);
        assertThat(get("/api/scans?offset=-1").statusCode()).isEqualTo(400);
        assertThat(post("../repo-a").statusCode()).isEqualTo(400);
        assertThat(post(repositoryRoot.toString()).statusCode()).isEqualTo(400);
        assertThat(post("missing").statusCode()).isEqualTo(400);
        properties.setRepositoryBase("");
        assertThat(post("repo-a").statusCode()).isEqualTo(503);
        assertThat(get("/api/repositories").statusCode()).isEqualTo(503);
        assertThat(store.list(100, 0).scans().totalCount()).isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for publication test");
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(String repository) throws Exception {
        String body = mapper.writeValueAsString(new ScanRequest(repository));
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/scans"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode json(HttpResponse<String> response) { return mapper.readTree(response.body()); }
    private String git(String... arguments) throws Exception {
        var command = new java.util.ArrayList<>(List.of("git", "-C", repositoryRoot.toString()));
        command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String result = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor()).withFailMessage(result).isZero();
        return result;
    }
}
