package com.oneil.legacy.framework;

import static org.assertj.core.api.Assertions.*;
import static com.oneil.legacy.framework.FrameworkIndexerTests.*;
import com.oneil.legacy.PostgresTestSupport;
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
class FrameworkIntegrationTests extends PostgresTestSupport {
    @TempDir Path root;
    @Autowired ScanService scans;
    @Autowired ScanStore scanStore;
    @Autowired ScanProperties properties;
    @Autowired SymbolStore symbols;
    @Autowired FrameworkQueries queries;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;
    @BeforeEach void fixture() throws Exception {
        jdbc.execute("TRUNCATE java_relationship, java_symbol, source_file, analysis_error, active_scan, scan");
        jdbc.update("INSERT INTO active_scan(singleton) VALUES (true)");
        copyFixture(root);
        properties.setRepositoryBase(root.getParent().toString());
        properties.setAnalyzerVersion("struts-spring-index-3-test");
    }
    @Test void restEntryPointsAndTraceReturnWiringEvidenceAndFreshness() throws Exception {
        var scan = scans.scan(root.getFileName().toString());
        assertThat(scan.scan().status()).isEqualTo(ScanModel.Status.COMPLETED);
        assertThat(scan.errors().items()).anyMatch(e -> e.code().equals("XML_PARSE"));
        var entryResponse = get("/api/entry-points?path=" + encode("/customer/search"));
        assertThat(entryResponse.statusCode()).isEqualTo(200);
        var entries = mapper.readTree(entryResponse.body());
        assertThat(entries.path("candidates").path("items").get(0).path("stableId").asText()).isEqualTo(ROUTE);
        var response = get("/api/entry-points/trace?entryId=" + encode(ROUTE));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(response.body()).path("freshness").path("scanId").asText()).isEqualTo(scan.scan().id().toString());
        var trace = queries.trace(ROUTE, 8, 100);
        assertThat(trace.truncated()).isFalse();
        assertThat(trace.paths()).singleElement().satisfies(path -> {
            assertThat(path.components()).containsExactly("/customer/search", "java:type:demo.CustomerAction", "java:type:demo.CustomerServiceImpl", "java:type:demo.CustomerDAOImpl");
            assertThat(path.resolutionState()).isEqualTo("INFERRED");
            assertThat(path.termination()).isEqualTo("LEAF");
            assertThat(path.evidence()).extracting(Relationship::type).contains("ROUTES_TO", "WIRES_TO", "INJECTS");
            assertThat(path.evidence()).allSatisfy(e -> { assertThat(e.line()).isPositive(); assertThat(e.column()).isPositive(); });
        });
        assertThat(symbols.detail("java:type:demo.CustomerService", 100, 0).outgoing().items())
                .anyMatch(e -> e.type().equals("WIRES_TO") && "java:type:demo.CustomerServiceImpl".equals(e.targetId()));
        assertThat(response.body()).doesNotContain("fixture-secret-must-not-escape");
        var beanDetail = get("/api/symbols/detail?stableId=" + encode(SERVICE));
        assertThat(beanDetail.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(beanDetail.body()).path("symbol").path("stableId").asText()).isEqualTo(SERVICE);
        var beanUsages = get("/api/symbols/usages?stableId=" + encode(SERVICE));
        assertThat(beanUsages.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(beanUsages.body()).path("usages").path("items").get(0).path("sourceId").asText()).isEqualTo(ACTION);
        assertThat(queries.entries("", 1, 0).candidates().truncated()).isTrue();
        assertThat(get("/api/entry-points/trace?entryId=" + encode(ROUTE) + "&depth=9").statusCode()).isEqualTo(400);
        assertThat(get("/api/entry-points?limit=201").statusCode()).isEqualTo(400);
        assertThat(get("/api/entry-points/trace?entryId=missing").statusCode()).isEqualTo(404);
        var bounded = queries.trace(ROUTE, 0, 1);
        assertThat(bounded.truncated()).isTrue();
        assertThat(bounded.paths()).singleElement().satisfies(p -> assertThat(p.termination()).isEqualTo("DEPTH_BOUND"));
    }
    @Test void ambiguousRoutesReturnCandidatesAndWiringDoesNotChooseOne() throws Exception {
        Files.writeString(root.resolve("web/WEB-INF/struts-config-other.xml"), "<struts-config><action-mappings><action path='/customer/search' type='demo.CustomerAction'/></action-mappings></struts-config>");
        Files.writeString(root.resolve("applicationContext-other.xml"), "<beans><bean id='anotherAction' class='demo.CustomerAction'/></beans>");
        assertThat(scans.scan(root.getFileName().toString()).scan().status()).isEqualTo(ScanModel.Status.COMPLETED);
        assertThat(queries.entries("/customer/search", 100, 0).candidates().items()).hasSize(2);
        assertThat(queries.trace(ROUTE, 8, 100).paths()).allSatisfy(path -> {
            assertThat(path.resolutionState()).isEqualTo("UNRESOLVED");
            assertThat(path.components()).doesNotContain("java:type:demo.CustomerServiceImpl");
            assertThat(path.evidence()).anyMatch(e -> e.targetDescription() != null && e.targetDescription().contains("count=2"));
        });
    }
    @Test void beanCyclesAndAmbiguousInjectionsTerminateWithoutInventingPaths() throws Exception {
        Files.writeString(root.resolve("web/WEB-INF/wiring.xml"), "<beans><bean id='customerDao' class='demo.CustomerDAOImpl'><property name='back' ref='customerService'/></bean></beans>");
        scans.scan(root.getFileName().toString());
        assertThat(queries.trace(ROUTE, 8, 100).paths()).singleElement().satisfies(p -> assertThat(p.termination()).isEqualTo("CYCLE"));
        Files.writeString(root.resolve("web/WEB-INF/wiring.xml"), "<beans><bean id='customerDao' class='demo.CustomerDAOImpl'/><bean id='customerDao' class='demo.AlternativeDAO'/></beans>");
        scans.scan(root.getFileName().toString());
        assertThat(queries.trace(ROUTE, 8, 100).paths()).singleElement().satisfies(p -> {
            assertThat(p.resolutionState()).isEqualTo("UNRESOLVED");
            assertThat(p.components()).doesNotContain("java:type:demo.CustomerDAOImpl", "java:type:demo.AlternativeDAO");
        });
    }
    @Test void repeatScansAreStableDeletedMappingsDisappearAndRowsAreImmutable() throws Exception {
        var first = scans.scan(root.getFileName().toString());
        var initial = queries.trace(ROUTE, 8, 100).paths();
        assertThat(scans.scan(root.getFileName().toString()).scan().status()).isEqualTo(ScanModel.Status.COMPLETED);
        assertThat(queries.trace(ROUTE, 8, 100).paths()).isEqualTo(initial);
        assertThatThrownBy(() -> jdbc.update("UPDATE java_symbol SET simple_name='changed' WHERE scan_id=? AND kind='ROUTE'", first.scan().id())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM java_relationship WHERE scan_id=? AND evidence_type='XML'", first.scan().id())).isInstanceOf(DataAccessException.class);
        Files.delete(root.resolve(CONFIG));
        assertThat(scans.scan(root.getFileName().toString()).scan().status()).isEqualTo(ScanModel.Status.COMPLETED);
        assertThat(queries.entries("", 100, 0).candidates().items()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM java_symbol WHERE scan_id=? AND kind='ROUTE'", Integer.class, first.scan().id())).isEqualTo(2);
    }
    @Test void uncommittedFrameworkPublicationNeverLeaksToReaders() throws Exception {
        var first = scans.scan(root.getFileName().toString());
        String xml = Files.readString(root.resolve(CONFIG)).replace("/customer/search", "/customer/new");
        Files.writeString(root.resolve(CONFIG), xml);
        var inventory = inventory();
        UUID id = scanStore.create(root.toString(), "test"); scanStore.start(id);
        var ready = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var writer = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                scanStore.complete(id, inventory); ready.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            }));
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(queries.entries("/customer/new", 100, 0).candidates().items()).isEmpty();
                assertThat(queries.trace(ROUTE, 8, 100).freshness().scanId()).isEqualTo(first.scan().id());
            } finally { release.countDown(); }
            writer.get(10, TimeUnit.SECONDS);
        }
        assertThat(queries.entries("/customer/new", 100, 0).candidates().items()).hasSize(1);
    }
    @Test void failedFrameworkPersistenceRollsBackAndPreservesPreviousActiveScan() {
        var first = scans.scan(root.getFileName().toString());
        var failing = new FrameworkIndexer() {
            @Override public Index index(Path path, ScanModel.Inventory inventory, Index javaIndex) {
                var good = super.index(path, inventory, javaIndex);
                var broken = new ArrayList<>(good.relationships());
                broken.add(new Relationship(ROUTE, "missing:symbol", null, "WIRES_TO", "RESOLVED", CONFIG, 1, 1, "XML"));
                return new Index(good.symbols(), broken, good.errors());
            }
        };
        var failure = new ScanService(scanStore, new RepositoryInventory(), properties, new RepositoryCatalog(properties), new JavaSymbolIndexer(), new com.oneil.legacy.grails.GrailsIndexer(), failing, new com.oneil.legacy.database.DatabaseIndexer()).scan(root.getFileName().toString());
        assertThat(failure.scan().status()).isEqualTo(ScanModel.Status.FAILED);
        assertThat(queries.trace(ROUTE, 8, 100).freshness().scanId()).isEqualTo(first.scan().id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM java_symbol WHERE scan_id=?", Integer.class, failure.scan().id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM java_relationship WHERE scan_id=?", Integer.class, failure.scan().id())).isZero();
    }
    @Test void stagingRowsAndNoActiveSnapshotRemainInvisible() throws Exception {
        assertThat(queries.entries("", 100, 0).freshness()).isNull();
        assertThat(get("/api/entry-points/trace?entryId=" + encode(ROUTE)).statusCode()).isEqualTo(404);
        var inventory = inventory();
        UUID id = scanStore.create(root.toString(), "test"); scanStore.start(id);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            for (var file : inventory.files()) jdbc.update("INSERT INTO source_file VALUES (?, ?, ?, ?, ?, ?)", id,
                    file.relativePath(), file.fileType(), file.contentHash(), file.encoding(), file.sizeBytes());
            symbols.persist(id, inventory.javaIndex());
        });
        assertThat(queries.entries("", 100, 0).candidates().items()).isEmpty();
        scanStore.fail(id);
        assertThat(queries.entries("", 100, 0).candidates().items()).isEmpty();
    }
    @Test void v3MigrationPreservesCompletedV2SymbolsAndActivePointer() {
        String schema = "slice3_upgrade";
        Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema).defaultSchema(schema).target("2").load().migrate();
        UUID id = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jdbc.execute("SET LOCAL search_path TO slice3_upgrade");
            jdbc.update("INSERT INTO scan(id,status,repository_root,analyzer_version) VALUES (?, 'PENDING','fixture','v2')", id);
            jdbc.update("UPDATE scan SET status='RUNNING', started_at=now() WHERE id=?", id);
            jdbc.update("INSERT INTO source_file VALUES (?, 'A.java','JAVA', ?, 'UTF-8', 10)", id, "a".repeat(64));
            jdbc.update("INSERT INTO java_symbol VALUES (?, 'java:type:A', 'CLASS','A','A',null,'RESOLVED','A.java',1,1,1,1)", id);
            jdbc.update("UPDATE scan SET status='COMPLETED',completed_at=now(),file_count=1 WHERE id=?", id);
            jdbc.update("UPDATE active_scan SET scan_id=?", id);
        });
        var flyway = Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema).defaultSchema(schema).target("3").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1); flyway.validate();
        assertThat(jdbc.queryForObject("SELECT scan_id FROM slice3_upgrade.active_scan", UUID.class)).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT stable_id FROM slice3_upgrade.java_symbol", String.class)).isEqualTo("java:type:A");
        assertThatThrownBy(() -> jdbc.update("UPDATE slice3_upgrade.java_symbol SET simple_name='changed'")).isInstanceOf(DataAccessException.class);
    }
    private ScanModel.Inventory inventory() throws Exception {
        var inventory = new RepositoryInventory().collect(root);
        var index = new FrameworkIndexer().index(root, inventory, new JavaSymbolIndexer().index(root, inventory));
        return new ScanModel.Inventory(inventory.files(), index.errors(), inventory.gitCommitSha(), index);
    }
    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
