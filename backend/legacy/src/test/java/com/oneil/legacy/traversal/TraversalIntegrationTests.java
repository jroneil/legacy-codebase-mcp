package com.oneil.legacy.traversal;

import static org.assertj.core.api.Assertions.*;
import static com.oneil.legacy.traversal.TraversalEngine.*;
import static com.oneil.legacy.traversal.TraversalQueries.*;
import com.oneil.legacy.PostgresTestSupport;
import com.oneil.legacy.scan.*;
import com.oneil.legacy.symbol.*;
import com.oneil.legacy.symbol.JavaIndexModel.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TraversalIntegrationTests extends PostgresTestSupport {
    @Autowired TraversalQueries queries;
    @Autowired ScanStore scans;
    @Autowired ScanService scanner;
    @Autowired ScanProperties properties;
    @Autowired SymbolStore symbols;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;
    @TempDir java.nio.file.Path root;
    private final Bounds bounds = new Bounds(12, 100, 50);
    private static final String TABLE = "db:table:CUSTOMER";
    private final List<Symbol> nodes = new ArrayList<>();
    private final List<Relationship> edges = new ArrayList<>();
    @BeforeEach void fixture() {
        jdbc.execute("TRUNCATE java_relationship, java_symbol, source_file, analysis_error, active_scan, scan");
        jdbc.update("INSERT INTO active_scan(singleton) VALUES (true)");
        node("Action", "CLASS", "Action", "RESOLVED"); node("Service", "CLASS", "Service", "RESOLVED");
        node("DAO", "CLASS", "DAO", "RESOLVED"); node("OtherDAO", "CLASS", "OtherDAO", "RESOLVED");
        node(TABLE, "DATABASE_TABLE", "CUSTOMER", "RESOLVED"); node("Empty", "CLASS", "Empty", "RESOLVED");
        node("Dynamic", "QUERY_ARTIFACT", "Dynamic", "UNRESOLVED");
        edge("Action", "Service", "CALLS", "RESOLVED", null);
        edge("Service", "DAO", "CALLS", "INFERRED", null);
        edge("Action", "OtherDAO", "CALLS", "RESOLVED", null);
        edge("OtherDAO", TABLE, "READS_TABLE", "RESOLVED", null);
        edge("DAO", TABLE, "READS_TABLE", "RESOLVED", null);
        edge("DAO", TABLE, "WRITES_TABLE", "RESOLVED", null);
        edge("Service", "Action", "CALLS", "RESOLVED", null);
        edge("Service", null, "INJECTS", "UNRESOLVED", "candidates: DAO, OtherDAO");
        edge("Service", "Dynamic", "EXECUTES_QUERY", "RESOLVED", null);
    }
    private void node(String id, String kind, String name, String state) {
        nodes.add(new Symbol(id, kind, name, id, null, state, "Fixture.java", 1, 1, 20, 1));
    }
    private void edge(String from, String to, String type, String state, String description) {
        edges.add(new Relationship(from, to, description, type, state, "Fixture.java", edges.size() + 1, 1, "FIXTURE"));
    }
    private ScanModel.Inventory inventory() {
        return new ScanModel.Inventory(List.of(new ScanModel.SourceFile("Fixture.java", "JAVA", "a".repeat(64), "UTF-8", 100)),
                List.of(), "a".repeat(40), new Index(nodes, edges, List.of()));
    }
    private UUID publish() {
        UUID id = scans.create("fixture", "slice5-test"); scans.start(id); scans.complete(id, inventory()); return id;
    }
    private Answer tables(String name) { return queries.query(name, Mode.DATABASE_TABLES, Direction.OUTGOING, bounds); }
    @Test void directTransitiveReadWriteAlternatePathsAndEvidence() {
        var scan = publish();
        var direct = tables("DAO");
        assertThat(direct.tables()).hasSize(2).allMatch(TableImpact::direct);
        assertThat(direct.tables()).extracting(TableImpact::access).containsExactly("READ", "WRITE");
        var transitive = tables("Action");
        assertThat(transitive.freshness().scanId()).isEqualTo(scan);
        assertThat(transitive.tables()).hasSize(3).noneMatch(TableImpact::direct);
        assertThat(transitive.tables().getFirst().path().nodes()).containsExactly("Action", "OtherDAO", TABLE);
        assertThat(transitive.tables()).extracting(t -> t.path().resolutionState()).containsExactly("RESOLVED", "INFERRED", "INFERRED");
        assertThat(transitive.tables()).allSatisfy(t -> assertThat(t.path().evidence()).allSatisfy(e -> {
            assertThat(e.sourcePath()).isEqualTo("Fixture.java"); assertThat(e.line()).isPositive(); assertThat(e.column()).isPositive();
        }));
        assertThat(tables("Action")).isEqualTo(transitive);
        assertThat(transitive.traversal().frontiers()).anyMatch(p -> p.termination().equals("CYCLE"));
        assertThat(transitive.traversal().frontiers()).anyMatch(p -> p.resolutionState().equals("UNRESOLVED") && p.evidence().getLast().targetDescription().contains("OtherDAO"));
        assertThat(transitive.frontierSymbols()).anyMatch(c -> c.stableId().equals("Dynamic") && c.resolutionState().equals("UNRESOLVED"));
        assertThat(tables("Empty").tables()).isEmpty();
    }
    @Test void incomingTableUsagesKeepAccessConfidenceAndEvidenceDirection() {
        publish();
        var answer = queries.query("CUSTOMER", Mode.TABLE_USAGES, Direction.INCOMING, bounds);
        assertThat(answer.tables()).anyMatch(t -> t.direct() && t.componentId().equals("DAO") && t.access().equals("WRITE"));
        var action = answer.tables().stream().filter(t -> t.componentId().equals("Action") && t.access().equals("WRITE")).findFirst().orElseThrow();
        assertThat(action.path().nodes()).containsExactly(TABLE, "DAO", "Service", "Action");
        assertThat(action.path().resolutionState()).isEqualTo("INFERRED");
        assertThat(action.path().evidence().getFirst().sourceId()).isEqualTo("DAO");
    }
    @Test void ambiguousNamesAndBranchesAreNeverCollapsed() {
        node("other:DAO", "CLASS", "DAO", "RESOLVED"); publish();
        // Exact ID wins; use a shared simple name that is not itself an ID for ambiguity.
        node("one:Shared", "CLASS", "Shared", "RESOLVED"); node("two:Shared", "CLASS", "Shared", "RESOLVED"); publish();
        var answer = tables("Shared");
        assertThat(answer.selection()).isEqualTo("AMBIGUOUS");
        assertThat(answer.candidates()).extracting(Candidate::stableId).containsExactly("one:Shared", "two:Shared");
        assertThat(answer.traversal()).isNull();
        var trace = queries.query("Service", Mode.TRACE, Direction.OUTGOING, bounds);
        assertThat(trace.traversal().paths()).anyMatch(p -> p.termination().equals("UNRESOLVED") && p.evidence().getLast().targetId() == null);
        assertThat(tables("Action").tables()).anyMatch(t -> t.path().nodes().contains("OtherDAO"))
                .anyMatch(t -> t.path().nodes().contains("DAO"));
    }
    @Test void mappingIsNotReadAndScopedBindingsAndImportsDoNotInventImpact() {
        node("Entity", "CLASS", "Entity", "RESOLVED"); node("Interface", "INTERFACE", "Interface", "RESOLVED");
        edge("Entity", TABLE, "MAPS_TO_TABLE", "RESOLVED", null);
        edge("Interface", "DAO", "WIRES_TO", "RESOLVED", "binding=unrelatedBean; bean-ref=dao");
        edge("Empty", "DAO", "IMPORTS", "RESOLVED", null); publish();
        assertThat(tables("Entity").tables()).singleElement().satisfies(t -> {
            assertThat(t.access()).isEqualTo("MAPPING"); assertThat(t.direct()).isTrue();
        });
        assertThat(tables("Interface").tables()).isEmpty(); assertThat(tables("Empty").tables()).isEmpty();
        assertThat(queries.query("Interface", Mode.TRACE, Direction.OUTGOING, bounds).traversal().paths()).isNotEmpty();
    }
    @Test void restContractsBoundsCandidateLimitsAndMissingCases() throws Exception {
        assertThat(get("/trace?component=Action").statusCode()).isEqualTo(404);
        for (int i = 0; i < 105; i++) node("candidate:" + i, "CLASS", "Shared", "RESOLVED");
        publish();
        for (String endpoint : List.of("/trace?component=Action", "/trace?component=DAO&direction=INCOMING", "/database-tables?component=Action", "/table-usages?table=CUSTOMER")) {
            var response = get(endpoint); assertThat(response.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(response.body()).path("freshness").path("scanId").asText()).isNotBlank();
        }
        var response = mapper.readTree(get("/database-tables?component=Action&depth=1").body());
        assertThat(response.path("traversal").path("truncated").asBoolean()).isTrue();
        assertThat(response.path("traversal").path("truncationReasons").toString()).contains("DEPTH_LIMIT");
        response = mapper.readTree(get("/trace?component=Shared").body());
        assertThat(response.path("selection").asText()).isEqualTo("AMBIGUOUS");
        assertThat(response.path("candidates").size()).isEqualTo(100);
        assertThat(response.path("candidatesTruncated").asBoolean()).isTrue();
        for (String params : List.of("depth=17", "depth=-1", "limit=0", "limit=501", "fanOut=201", "direction=WRONG"))
            assertThat(get("/trace?component=Action&" + params).statusCode()).isEqualTo(400);
        assertThat(get("/trace?component=Missing").statusCode()).isEqualTo(404);
        assertThat(get("/table-usages?table=Action").statusCode()).isEqualTo(404);
        assertThat(get("/trace?component=%20").statusCode()).isEqualTo(400);
    }
    @Test void runningFailedAndUncommittedReplacementRemainInvisible() throws Exception {
        UUID first = publish();
        node("New", "CLASS", "New", "RESOLVED"); edge("Action", "New", "CALLS", "RESOLVED", null);
        UUID running = scans.create("fixture", "test"); scans.start(running);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jdbc.update("INSERT INTO source_file VALUES (?, 'Fixture.java','JAVA', ?, 'UTF-8',100)", running, "a".repeat(64));
            symbols.persist(running, inventory().javaIndex());
        });
        assertThat(tables("Action").freshness().scanId()).isEqualTo(first);
        assertThatThrownBy(() -> tables("New")).isInstanceOf(SymbolStore.SymbolNotFoundException.class);
        scans.fail(running);
        assertThat(tables("Action").freshness().scanId()).isEqualTo(first);
        UUID next = scans.create("fixture", "test"); scans.start(next);
        var ready = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var writer = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                scans.complete(next, inventory()); ready.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Timeout"); }
                catch (InterruptedException e) { throw new AssertionError(e); }
            }));
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(tables("Action").freshness().scanId()).isEqualTo(first);
                assertThat(get("/trace?component=New").statusCode()).isEqualTo(404);
            } finally { release.countDown(); }
            writer.get(10, TimeUnit.SECONDS);
        }
        assertThat(tables("New").freshness().scanId()).isEqualTo(next);
        nodes.removeIf(n -> n.stableId().equals("New")); edges.removeIf(e -> "New".equals(e.targetId())); publish();
        assertThatThrownBy(() -> tables("New")).isInstanceOf(SymbolStore.SymbolNotFoundException.class);
    }
    @Test void sqlFanOutBoundsAndOrderingSurviveReorderedSnapshotInsertion() throws Exception {
        for (int i = 0; i < 220; i++) {
            String id = String.format("fan:%03d", i);
            node(id, "CLASS", id, "RESOLVED"); edge("Empty", id, "CALLS", "RESOLVED", null);
        }
        publish();
        var first = queries.query("Empty", Mode.TRACE, Direction.OUTGOING, new Bounds(12, 100, 5));
        assertThat(first.traversal().paths()).hasSize(5);
        assertThat(first.traversal().truncationReasons()).contains("FAN_OUT_LIMIT");
        assertThat(first.traversal().paths()).extracting(Path::end).containsExactly("fan:000", "fan:001", "fan:002", "fan:003", "fan:004");
        Collections.reverse(edges); Collections.reverse(nodes); publish();
        assertThat(queries.query("Empty", Mode.TRACE, Direction.OUTGOING, new Bounds(12, 100, 5)).traversal()).isEqualTo(first.traversal());
        var response = mapper.readTree(get("/database-tables?component=Action&limit=1").body());
        assertThat(response.path("tables").size()).isEqualTo(1);
        assertThat(response.path("traversal").path("truncationReasons").toString()).contains("RESULT_LIMIT");
    }
    @Test void actualStrutsSpringJavaSqlEvidenceReachesCustomerTable() throws Exception {
        var source = java.nio.file.Path.of(getClass().getResource("/fixtures/struts-spring").toURI());
        try (var files = Files.walk(source)) {
            for (var file : files.toList()) {
                var target = root.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file)) Files.createDirectories(target); else Files.copy(file, target);
            }
        }
        // Extend only the disposable fixture, never the analyzed repository or the shared Slice 3 fixture.
        Files.writeString(root.resolve("src/demo/CustomerDAOImpl.java"), """
                package demo;
                public class CustomerDAOImpl implements CustomerDAO {
                    public void find() {
                        try (java.sql.Connection c = null) {
                            var statement = c.prepareStatement("SELECT * FROM CUSTOMER");
                            statement.executeQuery();
                        } catch (Exception e) { }
                    }
                }
                """);
        configureRepository(root);
        assertThat(scanner.scan().scan().status()).isEqualTo(ScanModel.Status.COMPLETED);
        var answer = tables("/customer/search");
        assertThat(answer.tables()).isNotEmpty();
        var chain = answer.tables().stream().filter(t -> t.tableId().equals(TABLE) && t.path().nodes().contains("java:type:demo.CustomerDAOImpl")).findFirst().orElseThrow();
        assertThat(chain.path().nodes()).contains("java:type:demo.CustomerAction",
                "spring:bean:web/WEB-INF/applicationContext.xml#customerService", "java:type:demo.CustomerDAOImpl", TABLE);
        assertThat(chain.path().resolutionState()).isEqualTo("INFERRED");
        assertThat(chain.path().evidence()).allSatisfy(e -> { assertThat(e.line()).isPositive(); assertThat(e.sourcePath()).isNotBlank(); });
        assertThat(tables("/customer/search")).isEqualTo(answer);
    }
    private HttpResponse<String> get(String suffix) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/relationships" + suffix)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
