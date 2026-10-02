package com.oneil.legacy.grails;

import static org.assertj.core.api.Assertions.*;

import com.oneil.legacy.PostgresTestSupport;
import com.oneil.legacy.mcp.McpTools;
import com.oneil.legacy.scan.*;
import com.oneil.legacy.traversal.TraversalEngine.Bounds;
import com.oneil.legacy.traversal.TraversalEngine.Direction;
import com.oneil.legacy.traversal.TraversalQueries;
import com.oneil.legacy.traversal.TraversalQueries.Mode;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Grails 2.6 end-to-end evidence through the existing normalized model, REST and MCP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Grails26IntegrationTests extends PostgresTestSupport {
    static final String MAPPINGS = "grails-app/conf/UrlMappings.groovy";
    static final String ROUTE_URI = "/customer/$id";
    static final String ROUTE = "grails:route:" + MAPPINGS + "#" + ROUTE_URI;
    static final String NAMED_ROUTE = "grails:route:" + MAPPINGS + "#/customers";
    static final String SHOW = "groovy:method:demo.CustomerController#show(Long)";
    static final String FINDER = "groovy:method:demo.CustomerService#findByLastName(String)";
    static final String CUSTOMER = "groovy:type:demo.Customer";
    static final String TABLE = "db:table:CUSTOMER";

    @TempDir Path root;
    @Autowired ScanService scans;
    @Autowired ScanProperties properties;
    @Autowired TraversalQueries queries;
    @Autowired McpTools tools;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;
    private final Bounds bounds = new Bounds(12, 100, 50);

    @BeforeEach void fixture() throws Exception {
        jdbc.execute("TRUNCATE java_relationship, java_symbol, source_file, analysis_error, active_scan, scan");
        jdbc.update("INSERT INTO active_scan(singleton) VALUES (true)");
        Grails26IndexerTests.copyFixture(root);
        properties.setRepositoryBase(root.getParent().toString());
        properties.setAnalyzerVersion("grails-2.6-index-test");
    }

    @Test void grails26RouteToTableFlowIsTraceableThroughRestAndMcp() throws Exception {
        var scan = scans.scan(root.getFileName().toString());
        assertThat(scan.scan().status()).isEqualTo(ScanModel.Status.COMPLETED);
        assertThat(scan.scan().analyzerVersion()).isEqualTo("grails-2.6-index-test");

        // /customer/$id -> CustomerController.show -> CustomerService.findByLastName -> Customer -> CUSTOMER
        var answer = queries.query(ROUTE_URI, Mode.TRACE, Direction.OUTGOING, bounds);
        assertThat(answer.freshness().scanId()).isEqualTo(scan.scan().id());
        assertThat(answer.traversal().paths()).anySatisfy(path -> assertThat(path.nodes()).containsExactly(ROUTE, SHOW, FINDER, TABLE));
        assertThat(answer.traversal().paths()).anySatisfy(path -> assertThat(path.nodes()).containsExactly(ROUTE, SHOW, FINDER, CUSTOMER, TABLE));
        assertThat(answer.traversal().paths()).filteredOn(path -> path.nodes().contains(TABLE)).isNotEmpty()
                .allSatisfy(path -> assertThat(path.resolutionState()).isEqualTo("INFERRED"));
        assertThat(answer.traversal().paths()).allSatisfy(path -> assertThat(path.evidence()).allSatisfy(edge -> {
            assertThat(edge.sourcePath()).endsWith(".groovy");
            assertThat(edge.line()).isPositive();
        }));

        // The Grails 2.x named mapping is a first-class route with the same semantics.
        var named = queries.query("/customers", Mode.TRACE, Direction.OUTGOING, bounds);
        assertThat(named.traversal().paths()).anySatisfy(path -> assertThat(path.nodes()).containsExactly(NAMED_ROUTE,
                "groovy:method:demo.CustomerController#list()", "groovy:method:demo.CustomerService#findAllCustomers()", TABLE));

        var impact = queries.query(ROUTE_URI, Mode.DATABASE_TABLES, Direction.OUTGOING, bounds);
        assertThat(impact.tables()).anyMatch(t -> t.tableId().equals(TABLE) && t.access().equals("READ") && !t.direct());
        assertThat(impact.tables()).anyMatch(t -> t.tableId().equals(TABLE) && t.access().equals("MAPPING"));

        JsonNode rest = json("/api/relationships/trace?component=" + encode(ROUTE_URI) + "&depth=12&limit=100&fanOut=50");
        assertThat(rest.path("freshness").path("scanId").asText()).isEqualTo(scan.scan().id().toString());
        assertThat(nodePaths(rest.path("traversal"))).contains(List.of(ROUTE, SHOW, FINDER, TABLE));
        assertThat(json("/api/entry-points?path=" + encode(ROUTE_URI)).path("candidates").path("totalCount").asInt()).isEqualTo(1);
        assertThat(json("/api/entry-points?path=" + encode("/customers")).path("candidates").path("items").toString())
                .contains("name=customerList");

        var mcp = tools.traceComponent(ROUTE_URI, Direction.OUTGOING, 12, 100, 50);
        assertThat(mcp.error()).isNull();
        assertThat(mcp.traversal().paths().stream().map(p -> p.nodes()).toList()).contains(List.of(ROUTE, SHOW, FINDER, TABLE));
    }

    @Test void legacyAnalysisErrorsAndSnapshotIsolationArePreserved() throws Exception {
        var first = scans.scan(root.getFileName().toString()).scan().id();
        assertThat(jdbc.queryForList("SELECT code, stage FROM analysis_error WHERE scan_id=? AND code='GROOVY_PARSE'", first))
                .singleElement().satisfies(row -> assertThat(row.get("stage")).isEqualTo("GROOVY"));
        var symbolCount = jdbc.queryForObject("SELECT count(*) FROM java_symbol WHERE scan_id=?", Integer.class, first);
        var second = scans.scan(root.getFileName().toString()).scan().id();
        assertThat(second).isNotEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM java_symbol WHERE scan_id=?", Integer.class, first)).isEqualTo(symbolCount);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM java_symbol WHERE scan_id=?", Integer.class, second)).isEqualTo(symbolCount);
        assertThat(jdbc.queryForObject("SELECT scan_id FROM active_scan WHERE singleton=true", UUID.class)).isEqualTo(second);
    }

    @Test void grails3AndStrutsBehaviourIsUnchanged() throws Exception {
        GrailsIntegrationTests.copyFixture("/fixtures/grails", root.resolve("grails3"));
        properties.setRepositoryBase(root.toString());
        var grails3 = scans.scan("grails3");
        assertThat(grails3.scan().status()).isEqualTo(ScanModel.Status.COMPLETED);
        assertThat(queries.query("/customer/$id", Mode.TRACE, Direction.OUTGOING, bounds).traversal().paths())
                .anySatisfy(path -> assertThat(path.nodes()).containsExactly(
                        "grails:route:grails-app/controllers/demo/UrlMappings.groovy#/customer/$id",
                        "groovy:method:demo.CustomerController#show(Long)",
                        "groovy:method:demo.CustomerService#findByLastName(String)",
                        TABLE));
        // No Grails 2.6-only artefacts appear in a 3.x snapshot.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM java_symbol WHERE scan_id=? AND stable_id LIKE 'grails:route:grails-app/conf/%'",
                Integer.class, grails3.scan().id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM java_symbol WHERE scan_id=? AND stable_id = 'db:table:customer_order'",
                Integer.class, grails3.scan().id())).isEqualTo(1);

        GrailsIntegrationTests.copyFixture("/fixtures/struts-spring", root.resolve("struts"));
        var struts = scans.scan("struts");
        assertThat(struts.scan().status()).isEqualTo(ScanModel.Status.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM java_symbol WHERE scan_id=? AND (stable_id LIKE 'groovy:%' OR stable_id LIKE 'grails:%')",
                Integer.class, struts.scan().id())).isZero();
        assertThat(jdbc.queryForList("SELECT resolution_state FROM java_relationship WHERE scan_id=? AND source_id=? AND relationship_type='ROUTES_TO'",
                String.class, struts.scan().id(), "struts:route:web/WEB-INF/struts-config.xml#/customer/search")).contains("RESOLVED");
    }

    private JsonNode json(String path) throws Exception {
        var response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path).isEqualTo(200);
        return mapper.readTree(response.body());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static List<List<String>> nodePaths(JsonNode traversal) {
        List<List<String>> paths = new ArrayList<>();
        for (JsonNode path : traversal.path("paths")) {
            List<String> nodes = new ArrayList<>();
            path.path("nodes").forEach(node -> nodes.add(node.asText()));
            paths.add(nodes);
        }
        return paths;
    }
}
