package com.oneil.legacy.mcp;

import static com.oneil.legacy.mcp.McpTestFixture.*;
import static com.oneil.legacy.traversal.TraversalEngine.Direction;
import static org.assertj.core.api.Assertions.*;

import com.oneil.legacy.mcp.McpResponses.*;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;

/** Equivalent deterministic queries must return the same indexed evidence through REST and MCP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpRestEquivalenceTests extends McpIntegrationSupport {
    @BeforeEach
    void fixture() {
        reset();
        publish(McpTestFixture.standard());
    }

    @Test
    void symbolSearchMatchesRest() throws Exception {
        var mcp = tools.searchSymbols("Customer", 50, null);
        JsonNode rest = json("/api/symbols/search?q=Customer&limit=50");
        assertThat(mcp.totalCount()).isEqualTo(rest.path("candidates").path("totalCount").asLong());
        assertThat(mcp.symbols()).extracting(SymbolView::stableId)
                .containsExactlyElementsOf(names(rest.path("candidates").path("items"), "stableId"));
        assertThat(mcp.freshness().scanId()).isEqualTo(rest.path("freshness").path("scanId").asText());
    }

    @Test
    void symbolDetailAndUsagesMatchRest() throws Exception {
        var mcp = tools.getSymbol(ACTION, 50, null);
        JsonNode rest = json("/api/symbols/detail?stableId=" + ACTION + "&limit=50");
        assertThat(mcp.symbol().stableId()).isEqualTo(rest.path("symbol").path("stableId").asText());
        assertThat(mcp.outgoing()).extracting(RelationshipView::type)
                .containsExactlyElementsOf(names(rest.path("outgoing").path("items"), "type"));
        assertThat(mcp.freshness().scanId()).isEqualTo(rest.path("freshness").path("scanId").asText());

        var usages = tools.findUsages(DAO, 50, null);
        JsonNode restUsages = json("/api/symbols/usages?stableId=" + DAO + "&limit=50");
        assertThat(usages.usages()).extracting(RelationshipView::sourceId)
                .containsExactlyElementsOf(names(restUsages.path("usages").path("items"), "sourceId"));
        assertThat(usages.usages()).extracting(RelationshipView::resolutionState)
                .containsExactlyElementsOf(names(restUsages.path("usages").path("items"), "resolutionState"));
    }

    @Test
    void entryPointsMatchRest() throws Exception {
        var mcp = tools.listEntryPoints("", 50, null);
        JsonNode rest = json("/api/entry-points?limit=50");
        assertThat(mcp.entryPoints()).extracting(EntryPointView::stableId)
                .containsExactlyElementsOf(names(rest.path("candidates").path("items"), "stableId"));
        assertThat(mcp.totalCount()).isEqualTo(rest.path("candidates").path("totalCount").asLong());
    }

    @Test
    void traversalMatchesRestIncludingAmbiguityAndPathConfidence() throws Exception {
        var mcp = tools.traceComponent(ACTION, Direction.OUTGOING, 12, 100, 50);
        JsonNode rest = json("/api/relationships/trace?component=" + ACTION + "&depth=12&limit=100&fanOut=50");
        assertThat(mcp.selection()).isEqualTo(rest.path("selection").asText());
        assertThat(nodePaths(mcp.traversal())).containsExactlyElementsOf(nodePaths(rest.path("traversal")));
        assertThat(mcp.traversal().paths()).extracting(PathView::resolutionState)
                .containsExactlyElementsOf(names(rest.path("traversal").path("paths"), "resolutionState"));
        assertThat(mcp.traversal().truncated()).isEqualTo(rest.path("traversal").path("truncated").asBoolean());
        assertThat(mcp.truncated()).isEqualTo(rest.path("traversal").path("truncated").asBoolean());
    }

    @Test
    void tableImpactMatchesRest() throws Exception {
        var mcp = tools.findTableUsages("CUSTOMER", 12, 100, 50);
        JsonNode rest = json("/api/relationships/table-usages?table=CUSTOMER&depth=12&limit=100&fanOut=50");
        assertThat(mcp.tables()).extracting(TableImpactView::tableId)
                .containsExactlyElementsOf(names(rest.path("tables"), "tableId"));
        assertThat(mcp.tables()).extracting(TableImpactView::access)
                .containsExactlyElementsOf(names(rest.path("tables"), "access"));
        assertThat(mcp.tables()).extracting(TableImpactView::direct)
                .containsExactlyElementsOf(StreamSupport.stream(rest.path("tables").spliterator(), false)
                        .map(node -> node.path("direct").asBoolean()).toList());
    }

    private JsonNode json(String path) throws Exception {
        var response = get(path);
        assertThat(response.statusCode()).isEqualTo(200);
        return mapper.readTree(response.body());
    }

    private static List<String> names(JsonNode array, String field) {
        return StreamSupport.stream(array.spliterator(), false).map(node -> node.path(field).asText()).toList();
    }

    private static List<List<String>> nodePaths(TraversalView traversal) {
        return traversal.paths().stream().map(PathView::nodes).toList();
    }

    private static List<List<String>> nodePaths(JsonNode traversal) {
        return StreamSupport.stream(traversal.path("paths").spliterator(), false)
                .map(path -> StreamSupport.stream(path.path("nodes").spliterator(), false).map(JsonNode::asText).toList())
                .toList();
    }
}
