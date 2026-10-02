package com.oneil.legacy.framework;

import static com.oneil.legacy.traversal.TraversalEngine.Direction;
import static com.oneil.legacy.traversal.TraversalQueries.*;
import static org.assertj.core.api.Assertions.*;

import com.oneil.legacy.PostgresTestSupport;
import com.oneil.legacy.mcp.McpTools;
import com.oneil.legacy.scan.*;
import com.oneil.legacy.symbol.SymbolStore;
import com.oneil.legacy.traversal.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SpringBootIntegrationTests extends PostgresTestSupport {
    @TempDir Path root;
    @Autowired ScanService scans;
    @Autowired ScanProperties properties;
    @Autowired FrameworkQueries framework;
    @Autowired TraversalQueries traversal;
    @Autowired SymbolStore symbols;
    @Autowired McpTools tools;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;
    private final TraversalEngine.Bounds bounds = new TraversalEngine.Bounds(12, 200, 100);

    @BeforeEach void fixture() throws Exception {
        jdbc.execute("TRUNCATE java_relationship, java_symbol, source_file, analysis_error, active_scan, scan");
        jdbc.update("INSERT INTO active_scan(singleton) VALUES (true)");
        SpringAnnotationIndexerTests.copyFixture(root);
        configureRepository(root);
        properties.setAnalyzerVersion("legacy-analyzer-1-spring-boot-test");
    }

    @Test void routesTraverseThroughServiceRepositoryEntityAndTableWithWeakestEdgeConfidence() {
        var scan = scans.scan();
        assertThat(scan.scan().status()).isEqualTo(ScanModel.Status.COMPLETED);
        assertThat(scan.errors().items()).anyMatch(error -> error.code().equals("JAVA_PARSE"))
                .anyMatch(error -> error.code().equals("SPRING_INJECTION_AMBIGUOUS"))
                .anyMatch(error -> error.code().equals("SPRING_INJECTION_MISSING"));

        String getRoute = route("/customers/{id}", "GET");
        var readImpact = traversal.query(getRoute, Mode.DATABASE_TABLES, Direction.OUTGOING, bounds);
        assertThat(readImpact.tables()).anySatisfy(table -> {
            assertThat(table.tableId()).isEqualTo("db:table:CUSTOMER");
            assertThat(table.access()).isEqualTo("READ");
            assertThat(table.path().nodes()).containsSubsequence(
                    "java:method:demo.CustomerController#get(java.lang.Long)",
                    "java:method:demo.CustomerService#find(java.lang.Long)",
                    "java:method:demo.CustomerRepository#findById(java.lang.Long)",
                    "db:table:CUSTOMER");
            assertThat(table.path().resolutionState()).isEqualTo("INFERRED");
        });
        assertThat(readImpact.tables()).anySatisfy(table -> {
            assertThat(table.tableId()).isEqualTo("db:table:CUSTOMER");
            assertThat(table.access()).isEqualTo("MAPPING");
            assertThat(table.path().nodes()).containsSubsequence(
                    "java:method:demo.CustomerRepository#findById(java.lang.Long)",
                    "java:type:demo.Customer", "db:table:CUSTOMER");
            assertThat(table.path().resolutionState()).isEqualTo("RESOLVED");
            assertThat(table.path().evidence()).allSatisfy(edge -> {
                assertThat(edge.sourcePath()).isNotBlank();
                assertThat(edge.line()).isPositive();
                assertThat(edge.column()).isPositive();
            });
        });

        String postRoute = route("/customers", "POST");
        var writeImpact = traversal.query(postRoute, Mode.DATABASE_TABLES, Direction.OUTGOING, bounds);
        assertThat(writeImpact.tables()).anySatisfy(table -> {
            assertThat(table.tableId()).isEqualTo("db:table:CUSTOMER");
            assertThat(table.access()).isEqualTo("WRITE");
            assertThat(table.path().nodes()).containsSubsequence(
                    "java:method:demo.CustomerController#create(demo.Customer)",
                    "java:method:demo.CustomerService#save(demo.Customer)",
                    "java:method:demo.CustomerRepository#save(demo.Customer)",
                    "db:table:CUSTOMER");
            assertThat(table.path().resolutionState()).isEqualTo("INFERRED");
        });
    }

    @Test void springEvidenceIsQueryableThroughSharedRestAndMcpServices() throws Exception {
        var scan = scans.scan().scan();
        String route = route("/customers/{id}", "GET");
        JsonNode rest = json("/api/relationships/trace?component=" + encode(route)
                + "&depth=12&limit=200&fanOut=100");
        var mcp = tools.traceComponent(route, Direction.OUTGOING, 12, 200, 100);
        assertThat(mcp.selection()).isEqualTo(rest.path("selection").asText());
        assertThat(mcp.freshness().scanId()).isEqualTo(scan.id().toString());
        assertThat(mcp.traversal().paths()).extracting(path -> path.nodes())
                .containsExactlyElementsOf(StreamSupport.stream(rest.path("traversal").path("paths").spliterator(), false)
                        .map(path -> values(path.path("nodes"), null)).toList());

        var service = symbols.detail("spring:bean:annotation:demo.CustomerService#customerService", 100, 0);
        assertThat(service.symbol().kind()).isEqualTo("BEAN");
        assertThat(service.outgoing().items()).extracting(edge -> edge.evidenceType())
                .contains("SPRING_COMPONENT", "SPRING_INJECTION");

        JsonNode entries = json("/api/entry-points?path=" + encode("/customers/{id}") + "&limit=50");
        assertThat(values(entries.path("candidates").path("items"), "stableId")).contains(route);
        assertThat(tools.listEntryPoints("/customers/{id}", 50, null).entryPoints())
                .extracting(entry -> entry.stableId()).contains(route);
    }

    private String route(String path, String method) {
        return framework.entries(path, 100, 0).candidates().items().stream()
                .filter(entry -> entry.dispatchParameter().contains("httpMethod=" + method))
                .map(FrameworkQueries.EntryPoint::stableId).findFirst().orElseThrow();
    }

    private JsonNode json(String path) throws Exception {
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return mapper.readTree(response.body());
    }

    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private static List<String> values(JsonNode array, String field) {
        return StreamSupport.stream(array.spliterator(), false)
                .map(node -> field == null ? node.asText() : node.path(field).asText()).toList();
    }
}
