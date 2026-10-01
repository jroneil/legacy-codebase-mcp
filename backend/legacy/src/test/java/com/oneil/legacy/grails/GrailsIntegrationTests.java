package com.oneil.legacy.grails;

import static org.assertj.core.api.Assertions.*;

import com.oneil.legacy.PostgresTestSupport;
import com.oneil.legacy.mcp.McpTools;
import com.oneil.legacy.scan.*;
import com.oneil.legacy.traversal.TraversalEngine.Bounds;
import com.oneil.legacy.traversal.TraversalEngine.Direction;
import com.oneil.legacy.traversal.TraversalQueries;
import com.oneil.legacy.traversal.TraversalQueries.Answer;
import com.oneil.legacy.traversal.TraversalQueries.Mode;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GrailsIntegrationTests extends PostgresTestSupport {
    static final String ROUTE_URI = "/customer/$id";
    static final String ROUTE = "grails:route:grails-app/controllers/demo/UrlMappings.groovy#" + ROUTE_URI;
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
        GrailsIndexerTests.copyFixture(root);
        properties.setRepositoryRoot(root.toString());
        properties.setAnalyzerVersion("grails-index-5-test");
    }

    Answer tables(String component) { return queries.query(component, Mode.DATABASE_TABLES, Direction.OUTGOING, bounds); }

    @Test void grailsFlowIsTraceableThroughServicesAndRestAndMcp() throws Exception {
        var scan = scans.scan();
        assertThat(scan.scan().status()).isEqualTo(ScanModel.Status.COMPLETED);
        assertThat(scan.scan().analyzerVersion()).isEqualTo("grails-index-5-test");

        // /customer/show -> CustomerController.show -> CustomerService.findByLastName -> Customer -> CUSTOMER
        var answer = queries.query(ROUTE_URI, Mode.TRACE, Direction.OUTGOING, bounds);
        assertThat(answer.freshness().scanId()).isEqualTo(scan.scan().id());
        assertThat(answer.traversal().paths()).anySatisfy(path -> assertThat(path.nodes())
                .containsExactly(ROUTE, SHOW, FINDER, TABLE));
        assertThat(answer.traversal().paths()).anySatisfy(path -> assertThat(path.nodes())
                .containsExactly(ROUTE, SHOW, FINDER, CUSTOMER, TABLE));
        // Intermediate prefixes stay resolved; every path that reaches the table is only inferred.
        assertThat(answer.traversal().paths()).filteredOn(path -> path.nodes().contains(TABLE)).isNotEmpty()
                .allSatisfy(path -> assertThat(path.resolutionState()).isEqualTo("INFERRED"));
        assertThat(answer.traversal().paths()).allSatisfy(path -> assertThat(path.evidence()).allSatisfy(edge -> {
            assertThat(edge.sourcePath()).endsWith(".groovy");
            assertThat(edge.line()).isPositive();
        }));

        // Table impact keeps READ and MAPPING distinct and never claims resolved confidence.
        var impact = tables(ROUTE_URI);
        assertThat(impact.tables()).anyMatch(t -> t.tableId().equals(TABLE) && t.access().equals("READ") && t.path().resolutionState().equals("INFERRED"));
        assertThat(impact.tables()).anyMatch(t -> t.tableId().equals(TABLE) && t.access().equals("MAPPING"));
        assertThat(impact.tables()).allSatisfy(t -> assertThat(t.path().evidence()).isNotEmpty());

        // REST exposes the same indexed evidence.
        JsonNode rest = json("/api/relationships/trace?component=" + encode(ROUTE_URI) + "&depth=12&limit=100&fanOut=50");
        assertThat(rest.path("freshness").path("scanId").asText()).isEqualTo(scan.scan().id().toString());
        assertThat(nodePaths(rest.path("traversal"))).contains(List.of(ROUTE, SHOW, FINDER, TABLE));
        assertThat(json("/api/entry-points?path=" + encode(ROUTE_URI)).path("candidates").path("totalCount").asInt()).isEqualTo(1);
        JsonNode detail = json("/api/symbols/detail?stableId=" + encode("groovy:type:demo.CustomerController"));
        assertThat(detail.path("symbol").path("kind").asText()).isEqualTo("CLASS");
        assertThat(detail.path("outgoing").path("items").toString()).contains("INJECTS", "demo.CustomerService");

        // MCP is the same service, so the Grails chain is identical.
        var mcp = tools.traceComponent(ROUTE_URI, Direction.OUTGOING, 12, 100, 50);
        assertThat(mcp.error()).isNull();
        assertThat(mcp.traversal().paths().stream().map(p -> p.nodes()).toList()).contains(List.of(ROUTE, SHOW, FINDER, TABLE));
    }

    @Test void grailsEvidenceIsStableAcrossRescansAndSnapshotsStayIsolated() throws Exception {
        var first = scans.scan().scan().id();
        var firstSymbols = signatures(first);
        assertThat(firstSymbols).isNotEmpty();
        var second = scans.scan().scan().id();
        assertThat(second).isNotEqualTo(first);
        assertThat(signatures(second)).isEqualTo(firstSymbols);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM java_symbol WHERE scan_id=?", Integer.class, first))
                .isEqualTo(firstSymbols.size());
        assertThat(jdbc.queryForObject("SELECT scan_id FROM active_scan WHERE singleton=true", UUID.class)).isEqualTo(second);
    }

    @Test void analysisErrorsArePersistedAndExposedWithStableBehaviour() throws Exception {
        var scan = scans.scan();
        var stored = jdbc.queryForList("SELECT code, relative_path, stage FROM analysis_error WHERE scan_id=? ORDER BY code",
                scan.scan().id());
        assertThat(stored).anySatisfy(row -> {
            assertThat(row.get("code")).isEqualTo("GROOVY_PARSE");
            assertThat(String.valueOf(row.get("relative_path"))).endsWith("Broken.groovy");
            assertThat(row.get("stage")).isEqualTo("GROOVY");
        });
        assertThat(stored).anySatisfy(row -> assertThat(row.get("code")).isEqualTo("GRAILS_METAPROGRAMMING"));
        JsonNode rest = json("/api/scans/" + scan.scan().id());
        assertThat(rest.path("errors").path("items").toString()).contains("GROOVY_PARSE", "GRAILS_METAPROGRAMMING");
    }

    @Test void ambiguousInjectionDoesNotInventTableImpact() throws Exception {
        scans.scan();
        var usages = queries.query("CUSTOMER", Mode.TABLE_USAGES, Direction.INCOMING, bounds);
        assertThat(usages.tables()).isNotEmpty();
        assertThat(usages.tables()).noneMatch(t -> t.componentId().contains("AmbiguousController"));
        // The controller is indexed, but its unresolved injection produces no confirmed path.
        var rows = jdbc.queryForList("SELECT target_id, target_description FROM java_relationship WHERE relationship_type='INJECTS' AND source_id='groovy:type:demo.AmbiguousController'");
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.get("target_id")).isNull();
            assertThat(String.valueOf(row.get("target_description"))).contains("demo.AmbiguousService", "demo.other.AmbiguousService");
        });
    }

    @Test void strutsRepositoryKeepsPriorBehaviourAndAddsNoGrailsEvidence() throws Exception {
        Path struts = root.resolve("struts-repository");
        copyFixture("/fixtures/struts-spring", struts);
        properties.setRepositoryRoot(struts.toString());
        var scan = scans.scan();
        assertThat(scan.scan().status()).isEqualTo(ScanModel.Status.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM java_symbol WHERE scan_id=? AND (stable_id LIKE 'groovy:%' OR stable_id LIKE 'grails:%')",
                Integer.class, scan.scan().id())).isZero();
        var route = "struts:route:web/WEB-INF/struts-config.xml#/customer/search";
        assertThat(jdbc.queryForList("SELECT resolution_state FROM java_relationship WHERE scan_id=? AND source_id=? AND relationship_type='ROUTES_TO'",
                String.class, scan.scan().id(), route)).contains("RESOLVED");
        assertThat(json("/api/entry-points?path=" + encode("/customer/search")).path("candidates").path("totalCount").asInt()).isEqualTo(1);
        assertThat(json("/api/relationships/trace?component=" + encode("/customer/search")).path("selection").asText()).isEqualTo("SELECTED");
    }

    static void copyFixture(String resource, Path target) throws Exception {
        Path source = Path.of(GrailsIntegrationTests.class.getResource(resource).toURI());
        try (var files = Files.walk(source)) {
            for (var file : files.toList()) {
                var destination = target.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file)) Files.createDirectories(destination); else Files.copy(file, destination);
            }
        }
    }

    private List<String> signatures(UUID scan) {
        return jdbc.queryForList("""
                SELECT stable_id || '|' || kind || '|' || resolution_state || '|' || source_path || '|' || start_line
                FROM java_symbol WHERE scan_id=? ORDER BY stable_id
                """, String.class, scan);
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
