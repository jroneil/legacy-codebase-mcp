package com.oneil.legacy.framework;

import static com.oneil.legacy.traversal.TraversalEngine.Direction;
import com.oneil.legacy.traversal.TraversalEngine.Bounds;
import static com.oneil.legacy.traversal.TraversalQueries.*;
import static org.assertj.core.api.Assertions.*;

import com.oneil.legacy.PostgresTestSupport;
import com.oneil.legacy.mcp.McpTools;
import com.oneil.legacy.scan.*;
import com.oneil.legacy.traversal.TraversalQueries;
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
class SpringMvcIntegrationTests extends PostgresTestSupport {
    @TempDir Path root;
    @Autowired ScanService scans;
    @Autowired ScanProperties properties;
    @Autowired FrameworkQueries framework;
    @Autowired TraversalQueries traversal;
    @Autowired McpTools tools;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;
    private final Bounds bounds = new Bounds(12, 100, 50);

    @BeforeEach void fixture() throws Exception {
        jdbc.execute("TRUNCATE java_relationship, java_symbol, source_file, analysis_error, active_scan, scan");
        jdbc.update("INSERT INTO active_scan(singleton) VALUES (true)");
        SpringMvcIndexerTests.copyFixture(root);
        configureRepository(root);
        properties.setAnalyzerVersion("legacy-analyzer-1-spring-mvc-test");
    }

    @Test void getAndPostRoutesReachServiceDaoAndCustomerTable() {
        var scan = scans.scan();
        assertThat(scan.scan().status()).isEqualTo(ScanModel.Status.COMPLETED);
        assertThat(scan.errors().items()).anyMatch(error -> error.code().equals("JAVA_PARSE"))
                .anyMatch(error -> error.code().equals("SPRING_MVC_MAPPING_UNRESOLVED"));

        String getRoute = framework.entries("/customers/{id}", 100, 0).candidates().items().stream()
                .map(FrameworkQueries.EntryPoint::stableId)
                .filter(id -> id.contains("CustomerController#getCustomer")).findFirst().orElseThrow();
        var entryTrace = framework.trace(getRoute, 8, 100);
        assertThat(entryTrace.paths()).singleElement().satisfies(path -> {
            assertThat(path.components()).containsExactly("/customers/{id}",
                    "java:method:demo.CustomerController#getCustomer(java.lang.String)");
            assertThat(path.resolutionState()).isEqualTo("RESOLVED");
            assertThat(path.termination()).isEqualTo("CONTROLLER_METHOD");
        });

        var reads = traversal.query(getRoute, Mode.DATABASE_TABLES, Direction.OUTGOING, bounds);
        var read = reads.tables().stream().filter(table -> table.tableId().equals("db:table:CUSTOMER")
                && table.access().equals("READ")).findFirst().orElseThrow();
        assertThat(read.path().nodes()).containsSubsequence(
                "java:method:demo.CustomerController#getCustomer(java.lang.String)",
                "java:method:demo.CustomerServiceImpl#find(java.lang.String)",
                "java:method:demo.CustomerDAO#find(java.lang.String)", "db:table:CUSTOMER");
        assertThat(read.path().resolutionState()).isEqualTo("RESOLVED");
        assertThat(read.path().evidence()).allSatisfy(edge -> {
            assertThat(edge.sourcePath()).isNotBlank();
            assertThat(edge.line()).isPositive();
            assertThat(edge.column()).isPositive();
        });

        String postRoute = framework.entries("/customers", 100, 0).candidates().items().stream()
                .filter(entry -> entry.dispatchParameter().equals("httpMethod=POST"))
                .map(FrameworkQueries.EntryPoint::stableId).findFirst().orElseThrow();
        var writes = traversal.query(postRoute, Mode.DATABASE_TABLES, Direction.OUTGOING, bounds);
        var write = writes.tables().stream().filter(table -> table.tableId().equals("db:table:CUSTOMER")
                && table.access().equals("WRITE")).findFirst().orElseThrow();
        assertThat(write.path().nodes()).containsSubsequence(
                "java:method:demo.CustomerController#create(java.lang.String)",
                "java:method:demo.CustomerServiceImpl#create(java.lang.String)",
                "java:method:demo.CustomerDAO#insert(java.lang.String)", "db:table:CUSTOMER");
        assertThat(write.path().resolutionState()).isEqualTo("RESOLVED");
    }

    @Test void restAndMcpExposeEquivalentSpringMvcEntryPointsAndTraversal() throws Exception {
        var scan = scans.scan().scan();
        JsonNode restEntries = json("/api/entry-points?path=" + encode("/customers/{id}") + "&limit=50");
        var mcpEntries = tools.listEntryPoints("/customers/{id}", 50, null);
        assertThat(mcpEntries.entryPoints()).extracting(entry -> entry.stableId())
                .containsExactlyElementsOf(values(restEntries.path("candidates").path("items"), "stableId"));
        assertThat(mcpEntries.freshness().scanId()).isEqualTo(scan.id().toString());

        String route = mcpEntries.entryPoints().stream().map(entry -> entry.stableId())
                .filter(id -> id.contains("CustomerController#getCustomer")).findFirst().orElseThrow();
        JsonNode restTrace = json("/api/relationships/trace?component=" + encode(route)
                + "&depth=12&limit=100&fanOut=50");
        var mcpTrace = tools.traceComponent(route, Direction.OUTGOING, 12, 100, 50);
        assertThat(mcpTrace.selection()).isEqualTo(restTrace.path("selection").asText());
        assertThat(mcpTrace.freshness().scanId()).isEqualTo(restTrace.path("freshness").path("scanId").asText());
        assertThat(mcpTrace.traversal().paths()).extracting(path -> path.nodes())
                .containsExactlyElementsOf(StreamSupport.stream(restTrace.path("traversal").path("paths").spliterator(), false)
                        .map(path -> values(path.path("nodes"), null)).toList());
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
