package com.oneil.legacy.database;

import static org.assertj.core.api.Assertions.*;
import com.oneil.legacy.PostgresTestSupport;
import com.oneil.legacy.framework.*;
import com.oneil.legacy.scan.*;
import com.oneil.legacy.symbol.*;
import com.oneil.legacy.symbol.JavaIndexModel.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DatabaseIntegrationTests extends PostgresTestSupport {
    @TempDir Path root;
    @Autowired ScanService scans;
    @Autowired ScanStore scanStore;
    @Autowired ScanProperties properties;
    @Autowired SymbolStore symbols;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;
    @BeforeEach void fixture() throws Exception {
        jdbc.execute("TRUNCATE java_relationship, java_symbol, source_file, analysis_error, active_scan, scan");
        jdbc.update("INSERT INTO active_scan(singleton) VALUES (true)");
        DatabaseIndexerTests.copyFixture(root);
        properties.setRepositoryRoot(root.toString()); properties.setAnalyzerVersion("database-usage-index-4-test");
    }
    @Test void restReturnsDirectTableUsageQueryMetadataAndLocalizedErrors() throws Exception {
        var scan = scans.scan();
        assertThat(scan.scan().status()).isEqualTo(ScanModel.Status.COMPLETED);
        assertThat(scan.errors().items()).extracting(e -> e.code()).contains("SQL_PARSE", "SQL_DYNAMIC", "HIBERNATE_XML_PARSE");
        var search = get("/api/symbols/search?q=" + encode("db:table:CUSTOMER"));
        assertThat(search.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(search.body()).path("candidates").path("items").get(0).path("kind").asText()).isEqualTo("DATABASE_TABLE");
        var usages = get("/api/symbols/usages?stableId=" + encode("db:table:CUSTOMER"));
        assertThat(usages.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(usages.body()).path("freshness").path("scanId").asText()).isEqualTo(scan.scan().id().toString());
        var edges = symbols.usages("db:table:CUSTOMER", 200, 0).usages().items();
        assertThat(edges).extracting(Relationship::type).contains("READS_TABLE", "WRITES_TABLE", "MAPS_TO_TABLE");
        assertThat(edges).allSatisfy(e -> { assertThat(e.line()).isPositive(); assertThat(e.column()).isPositive(); });
        var dynamic = symbols.search("", 200, 0).candidates().items().stream().filter(s -> s.kind().equals("QUERY_ARTIFACT") && s.signature().contains("tableName")).findFirst().orElseThrow();
        var detail = get("/api/symbols/detail?stableId=" + encode(dynamic.stableId()));
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(detail.body()).path("symbol").path("resolutionState").asText()).isEqualTo("UNRESOLVED");
        assertThat(mapper.readTree(detail.body()).path("outgoing").path("items").size()).isZero();
        assertThat(mapper.writeValueAsString(symbols.search("", 200, 0))).doesNotContain("fixture-secret-must-not-escape");
    }
    @Test void repeatedSnapshotsKeepIdentitiesAndDeletedDatabaseEvidenceDisappears() throws Exception {
        var first = scans.scan(); var baseline = symbols.search("", 200, 0).candidates().items();
        var usages = symbols.usages("db:table:CUSTOMER", 200, 0).usages().items();
        assertThat(scans.scan().scan().status()).isEqualTo(ScanModel.Status.COMPLETED);
        assertThat(symbols.search("", 200, 0).candidates().items()).isEqualTo(baseline);
        assertThat(symbols.usages("db:table:CUSTOMER", 200, 0).usages().items()).isEqualTo(usages);
        Files.delete(root.resolve("src/demo/CustomerDAO.java")); Files.delete(root.resolve("src/demo/SqlConstants.java")); Files.delete(root.resolve("Customer.hbm.xml"));
        assertThat(scans.scan().scan().status()).isEqualTo(ScanModel.Status.COMPLETED);
        assertThat(symbols.search("db:table:CUSTOMER", 200, 0).candidates().items()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM java_symbol WHERE scan_id=? AND kind='DATABASE_TABLE'", Integer.class, first.scan().id())).isPositive();
    }
    @Test void uncommittedDatabasePublicationLeavesOldModelVisible() throws Exception {
        var first = scans.scan();
        Files.writeString(root.resolve("src/demo/NewDAO.java"), "package demo; class NewDAO { void run(java.sql.Statement st) throws Exception { st.executeQuery(\"SELECT * FROM NEW_TABLE\"); } }");
        var inventory = inventory(); UUID id = scanStore.create(root.toString(), "test"); scanStore.start(id);
        var ready = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var writer = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                scanStore.complete(id, inventory); ready.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            }));
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                var search = symbols.search("db:table:NEW_TABLE", 100, 0);
                assertThat(search.candidates().items()).isEmpty(); assertThat(search.freshness().scanId()).isEqualTo(first.scan().id());
            } finally { release.countDown(); }
            writer.get(10, TimeUnit.SECONDS);
        }
        assertThat(symbols.search("db:table:NEW_TABLE", 100, 0).candidates().items()).hasSize(1);
    }
    @Test void failedDatabaseInsertionRollsBackAndPreservesPriorActiveSnapshot() {
        var first = scans.scan();
        var failing = new DatabaseIndexer() {
            @Override public Index index(Path root, ScanModel.Inventory inventory, Index existing) {
                var good = super.index(root, inventory, existing); var invalid = new ArrayList<>(good.relationships());
                invalid.add(new Relationship("missing:query", "db:table:CUSTOMER", null, "READS_TABLE", "RESOLVED", "Customer.hbm.xml", 1, 1, "JSQLPARSER"));
                return new Index(good.symbols(), invalid, good.errors());
            }
        };
        var failure = new ScanService(scanStore, new RepositoryInventory(), properties, new JavaSymbolIndexer(), new FrameworkIndexer(), failing).scan();
        assertThat(failure.scan().status()).isEqualTo(ScanModel.Status.FAILED);
        assertThat(symbols.search("CUSTOMER", 100, 0).freshness().scanId()).isEqualTo(first.scan().id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM java_symbol WHERE scan_id=?", Integer.class, failure.scan().id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM java_relationship WHERE scan_id=?", Integer.class, failure.scan().id())).isZero();
    }
    @Test void stagingDatabaseRowsStayHiddenAndPublishedEvidenceIsImmutable() throws Exception {
        var inventory = inventory(); UUID id = scanStore.create(root.toString(), "test"); scanStore.start(id);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            for (var file : inventory.files()) jdbc.update("INSERT INTO source_file VALUES (?, ?, ?, ?, ?, ?)", id, file.relativePath(), file.fileType(), file.contentHash(), file.encoding(), file.sizeBytes());
            symbols.persist(id, inventory.javaIndex());
        });
        assertThat(symbols.search("CUSTOMER", 100, 0).candidates().items()).isEmpty(); scanStore.fail(id);
        assertThat(symbols.search("CUSTOMER", 100, 0).candidates().items()).isEmpty();
        var complete = scans.scan();
        assertThatThrownBy(() -> jdbc.update("UPDATE java_symbol SET signature='changed' WHERE scan_id=? AND kind='QUERY_ARTIFACT'", complete.scan().id())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM java_relationship WHERE scan_id=? AND relationship_type='READS_TABLE'", complete.scan().id())).isInstanceOf(DataAccessException.class);
    }
    @Test void v4MigrationRetainsV3FrameworkAndJavaEvidence() {
        String schema = "slice4_upgrade";
        Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema).defaultSchema(schema).target("3").load().migrate();
        UUID id = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jdbc.execute("SET LOCAL search_path TO slice4_upgrade");
            jdbc.update("INSERT INTO scan(id,status,repository_root,analyzer_version) VALUES (?, 'PENDING','fixture','v3')", id);
            jdbc.update("UPDATE scan SET status='RUNNING', started_at=now() WHERE id=?", id);
            jdbc.update("INSERT INTO source_file VALUES (?, 'A.java','JAVA', ?, 'UTF-8', 10)", id, "a".repeat(64));
            jdbc.update("INSERT INTO java_symbol VALUES (?, 'java:type:A','CLASS','A','A',null,'RESOLVED','A.java',1,1,1,1)", id);
            jdbc.update("INSERT INTO java_symbol VALUES (?, 'route:A','ROUTE','/a','/a',null,'RESOLVED','A.java',1,1,1,1)", id);
            jdbc.update("INSERT INTO java_relationship VALUES (?, ?, 'route:A','java:type:A',null,'ROUTES_TO','RESOLVED','A.java',1,1,'XML')", id, UUID.randomUUID());
            jdbc.update("UPDATE scan SET status='COMPLETED', completed_at=now(), file_count=1 WHERE id=?", id);
            jdbc.update("UPDATE active_scan SET scan_id=?", id);
        });
        var flyway = Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema).defaultSchema(schema).target("4").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1); flyway.validate();
        assertThat(jdbc.queryForObject("SELECT scan_id FROM slice4_upgrade.active_scan", UUID.class)).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM slice4_upgrade.java_symbol", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT relationship_type FROM slice4_upgrade.java_relationship", String.class)).isEqualTo("ROUTES_TO");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM slice4_upgrade.java_relationship")).isInstanceOf(DataAccessException.class);
    }
    private ScanModel.Inventory inventory() throws Exception {
        var inventory = new RepositoryInventory().collect(root);
        var index = new DatabaseIndexer().index(root, inventory, new FrameworkIndexer().index(root, inventory, new JavaSymbolIndexer().index(root, inventory)));
        return new ScanModel.Inventory(inventory.files(), index.errors(), inventory.gitCommitSha(), index);
    }
    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
