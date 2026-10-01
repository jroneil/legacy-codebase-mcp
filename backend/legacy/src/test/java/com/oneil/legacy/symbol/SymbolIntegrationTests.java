package com.oneil.legacy.symbol;

import static com.oneil.legacy.symbol.JavaIndexModel.*;
import static org.assertj.core.api.Assertions.*;

import com.oneil.legacy.PostgresTestSupport;
import com.oneil.legacy.framework.FrameworkIndexer;
import com.oneil.legacy.scan.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
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
class SymbolIntegrationTests extends PostgresTestSupport {
    @TempDir Path root;
    @Autowired ScanService scans;
    @Autowired ScanStore scanStore;
    @Autowired ScanProperties properties;
    @Autowired SymbolStore symbols;
    @Autowired JdbcTemplate jdbc;
    @Autowired JavaSymbolIndexer indexer;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void fixture() throws Exception {
        jdbc.execute("TRUNCATE java_relationship, java_symbol, source_file, analysis_error, active_scan, scan");
        jdbc.update("INSERT INTO active_scan(singleton) VALUES (true)");
        Path source = Path.of(getClass().getResource("/fixtures/java-index").toURI());
        try (var files = Files.walk(source)) {
            for (Path file : files.toList()) {
                Path destination = root.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file)) Files.createDirectories(destination); else Files.copy(file, destination);
            }
        }
        properties.setRepositoryRoot(root.toString());
        properties.setAnalyzerVersion("java-symbol-index-2-test");
    }

    @Test
    void restSearchDetailAndUsagesReturnEvidenceAndAmbiguousCandidates() throws Exception {
        var scan = scans.scan();
        assertThat(scan.scan().status()).isEqualTo(ScanModel.Status.COMPLETED);
        assertThat(scan.errors().items()).anyMatch(e -> e.code().equals("JAVA_PARSE"));
        var response = get("/api/symbols/search?q=Service");
        assertThat(response.statusCode()).isEqualTo(200);
        var search = mapper.readTree(response.body());
        assertThat(search.path("freshness").path("scanId").asText()).isEqualTo(scan.scan().id().toString());
        assertThat(symbols.search("Service", 100, 0).candidates().items()).filteredOn(s -> s.kind().equals("CLASS") && s.simpleName().equals("Service"))
                .extracting(Symbol::stableId).containsExactly("java:type:demo.Service", "java:type:other.Service");
        String id = "java:method:demo.Service#ping(int)";
        var detail = get("/api/symbols/" + encode(id));
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(detail.body()).path("symbol").path("stableId").asText()).isEqualTo(id);
        var usages = get("/api/symbols/" + encode(id) + "/usages");
        assertThat(usages.statusCode()).isEqualTo(200);
        JsonNode edge = mapper.readTree(usages.body()).path("usages").path("items").get(0);
        assertThat(edge.path("sourceId").asText()).isEqualTo("java:method:demo.Service#run(java.lang.Long)");
        assertThat(edge.path("resolutionState").asText()).isEqualTo("RESOLVED");
        assertThat(edge.path("line").asInt()).isPositive();
        assertThat(edge.path("sourcePath").asText()).isEqualTo("app/src/main/java/demo/Service.java");
        assertThat(get("/api/symbols/" + encode("java:type:absent")).statusCode()).isEqualTo(404);
        assertThat(get("/api/symbols/search?limit=201").statusCode()).isEqualTo(400);
        var page = mapper.readTree(get("/api/symbols/search?q=ping&limit=1").body()).path("candidates");
        assertThat(page.path("totalCount").asInt()).isEqualTo(3);
        assertThat(page.path("items").size()).isEqualTo(1);
        assertThat(page.path("truncated").asBoolean()).isTrue();
        assertThat(symbols.search("%", 100, 0).candidates().items()).isEmpty();
        assertThat(detail.body() + usages.body()).doesNotContain("fixture-secret-must-not-escape");
    }

    @Test
    void repeatScansAreStableAndDeletedSymbolsDisappearOnlyFromNewSnapshot() throws Exception {
        var first = scans.scan();
        var baseline = symbols.search("", 200, 0).candidates().items();
        var relationships = symbols.detail("java:type:demo.Service", 200, 0).outgoing();
        var second = scans.scan();
        assertThat(second.scan().status()).isEqualTo(ScanModel.Status.COMPLETED);
        assertThat(symbols.search("", 200, 0).candidates().items()).isEqualTo(baseline);
        assertThat(symbols.detail("java:type:demo.Service", 200, 0).outgoing()).isEqualTo(relationships);
        Files.delete(root.resolve("other/src/main/java/other/Service.java"));
        var third = scans.scan();
        assertThat(third.scan().status()).isEqualTo(ScanModel.Status.COMPLETED);
        assertThat(symbols.search("", 200, 0).candidates().items()).noneMatch(s -> s.stableId().equals("java:type:other.Service"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM java_symbol WHERE scan_id=? AND stable_id='java:type:other.Service'",
                Integer.class, first.scan().id())).isEqualTo(1);
        assertThat(symbols.detail("java:type:demo.Caller", 200, 0).outgoing().items()).anyMatch(e -> e.type().equals("IMPORTS") && e.resolutionState().equals("UNRESOLVED"));
    }

    @Test
    void symbolReadersNeverSeeUncommittedPublication() throws Exception {
        var first = scans.scan();
        Files.writeString(root.resolve("New.java"), "class NewlyAdded {}");
        var inventory = inventory();
        UUID id = scanStore.create(root.toString(), "test");
        scanStore.start(id);
        var ready = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var writer = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                scanStore.complete(id, inventory);
                ready.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            }));
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                var search = symbols.search("NewlyAdded", 100, 0);
                assertThat(search.candidates().items()).isEmpty();
                assertThat(search.freshness().scanId()).isEqualTo(first.scan().id());
            } finally { release.countDown(); }
            writer.get(10, TimeUnit.SECONDS);
        }
        assertThat(symbols.search("NewlyAdded", 100, 0).candidates().items()).hasSize(1);
    }

    @Test
    void failedSymbolPublicationRollsBackAndKeepsPriorAnalysis() throws Exception {
        var first = scans.scan();
        var collected = inventory();
        Symbol duplicate = collected.javaIndex().symbols().getFirst();
        var broken = new Index(List.of(duplicate, duplicate), List.of(), List.of());
        var failing = new JavaSymbolIndexer() {
            @Override public Index index(Path path, ScanModel.Inventory ignored) { return broken; }
        };
        var result = new ScanService(scanStore, new RepositoryInventory(), properties, failing, new com.oneil.legacy.grails.GrailsIndexer(), new FrameworkIndexer(), new com.oneil.legacy.database.DatabaseIndexer()).scan();
        assertThat(result.scan().status()).isEqualTo(ScanModel.Status.FAILED);
        assertThat(symbols.search("", 100, 0).freshness().scanId()).isEqualTo(first.scan().id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM java_symbol WHERE scan_id=?", Integer.class, result.scan().id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_file WHERE scan_id=?", Integer.class, result.scan().id())).isZero();
    }

    @Test
    void stagingIsInvisibleAndPublishedSymbolsAndEdgesAreImmutable() throws Exception {
        assertThat(symbols.search("", 100, 0).freshness()).isNull();
        assertThat(get("/api/symbols/" + encode("java:type:demo.Service")).statusCode()).isEqualTo(404);
        var inventory = inventory();
        UUID id = scanStore.create(root.toString(), "test");
        scanStore.start(id);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            for (var file : inventory.files()) jdbc.update("INSERT INTO source_file VALUES (?, ?, ?, ?, ?, ?)", id,
                    file.relativePath(), file.fileType(), file.contentHash(), file.encoding(), file.sizeBytes());
            symbols.persist(id, inventory.javaIndex());
        });
        assertThat(symbols.search("", 100, 0).candidates().items()).isEmpty();
        scanStore.fail(id);
        assertThat(symbols.search("", 100, 0).candidates().items()).isEmpty();
        var complete = scans.scan();
        assertThatThrownBy(() -> jdbc.update("UPDATE java_symbol SET simple_name='changed' WHERE scan_id=?", complete.scan().id())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM java_relationship WHERE scan_id=?", complete.scan().id())).isInstanceOf(DataAccessException.class);
    }

    @Test
    void v2MigrationPreservesExistingV1Snapshot() {
        String schema = "slice2_upgrade";
        Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema).defaultSchema(schema).target("1").load().migrate();
        UUID id = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            jdbc.execute("SET LOCAL search_path TO slice2_upgrade");
            jdbc.update("INSERT INTO scan(id,status,repository_root,analyzer_version) VALUES (?, 'PENDING','fixture','v1')", id);
            jdbc.update("UPDATE scan SET status='RUNNING', started_at=now() WHERE id=?", id);
            jdbc.update("UPDATE scan SET status='COMPLETED', completed_at=now() WHERE id=?", id);
            jdbc.update("UPDATE active_scan SET scan_id=?", id);
        });
        var flyway = Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema).defaultSchema(schema).target("2").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        flyway.validate();
        assertThat(jdbc.queryForObject("SELECT scan_id FROM slice2_upgrade.active_scan", UUID.class)).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM slice2_upgrade.java_symbol", Integer.class)).isZero();
    }

    private ScanModel.Inventory inventory() throws Exception {
        var inventory = new RepositoryInventory().collect(root);
        var index = indexer.index(root, inventory);
        var errors = new ArrayList<>(inventory.errors());
        errors.addAll(index.errors());
        return new ScanModel.Inventory(inventory.files(), errors, inventory.gitCommitSha(), index);
    }
    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private String encode(String id) { return URLEncoder.encode(id, StandardCharsets.UTF_8); }
}
