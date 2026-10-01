package com.oneil.legacy.mcp;

import static com.oneil.legacy.mcp.McpTestFixture.*;
import static com.oneil.legacy.mcp.McpTools.*;
import static com.oneil.legacy.traversal.TraversalEngine.Direction;
import static org.assertj.core.api.Assertions.*;

import com.oneil.legacy.mcp.McpResponses.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;

/** MCP tool contract: bounded payloads, explicit truncation, preserved uncertainty and freshness metadata. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpToolContractTests extends McpIntegrationSupport {
    @BeforeEach
    void fixture() {
        reset();
    }

    @Test
    void searchSymbolsIsBoundedPaginatedAndFresh() {
        UUID scan = publish(McpTestFixture.standard());
        var first = tools.searchSymbols("Customer", 2, null);
        assertThat(first.error()).isNull();
        assertThat(first.freshness().scanId()).isEqualTo(scan.toString());
        assertThat(first.freshness().analyzerVersion()).isEqualTo("slice6-test");
        assertThat(first.freshness().gitCommitSha()).isEqualTo("b".repeat(40));
        assertThat(first.freshness().scanCompletedAt()).isNotBlank();
        assertThat(first.returnedCount()).isEqualTo(2);
        assertThat(first.totalCount()).isGreaterThan(2);
        assertThat(first.truncated()).isTrue();
        assertThat(first.nextCursor()).isEqualTo("2");

        var second = tools.searchSymbols("Customer", 2, first.nextCursor());
        assertThat(second.returnedCount()).isEqualTo(2);
        assertThat(second.symbols()).extracting(SymbolView::stableId)
                .doesNotContainAnyElementsOf(first.symbols().stream().map(SymbolView::stableId).toList());

        var all = tools.searchSymbols("Customer", 200, null);
        assertThat(all.truncated()).isFalse();
        assertThat(all.nextCursor()).isNull();
        assertThat(all.returnedCount()).isEqualTo((int) all.totalCount());
        assertThat(all.symbols()).allSatisfy(symbol -> assertThat(symbol.sourcePath()).isNotBlank());
    }

    @Test
    void getSymbolReturnsStableIdentityEvidenceLocationsAndBoundedOutgoing() {
        UUID scan = publish(McpTestFixture.standard());
        var detail = tools.getSymbol(ACTION, 50, null);
        assertThat(detail.error()).isNull();
        assertThat(detail.freshness().scanId()).isEqualTo(scan.toString());
        assertThat(detail.symbol().stableId()).isEqualTo(ACTION);
        assertThat(detail.symbol().kind()).isEqualTo("CLASS");
        assertThat(detail.symbol().resolutionState()).isEqualTo("RESOLVED");
        assertThat(detail.outgoing()).isNotEmpty();
        assertThat(detail.outgoing()).allSatisfy(edge -> {
            assertThat(edge.sourcePath()).isNotBlank();
            assertThat(edge.line()).isPositive();
            assertThat(edge.column()).isPositive();
            assertThat(edge.resolutionState()).isIn("RESOLVED", "INFERRED", "UNRESOLVED");
        });
        assertThat(detail.outgoing()).anyMatch(edge -> edge.type().equals("CONTAINS"));
        assertThat(detail.returnedCount()).isEqualTo(detail.outgoing().size());
    }

    @Test
    void findUsagesKeepsIncomingEvidenceAndResolutionState() {
        publish(McpTestFixture.standard());
        var dao = tools.findUsages(DAO, 50, null);
        assertThat(dao.error()).isNull();
        assertThat(dao.usages()).singleElement().satisfies(edge -> {
            assertThat(edge.sourceId()).isEqualTo(SERVICE);
            assertThat(edge.resolutionState()).isEqualTo("INFERRED");
            assertThat(edge.sourcePath()).isEqualTo(SERVICE_PATH);
        });
        // Containment is not a usage; the route still sees the action through ROUTES_TO.
        var route = tools.findUsages(ACTION, 50, null);
        assertThat(route.usages()).extracting(RelationshipView::type).containsExactly("ROUTES_TO");
        // Unresolved injection stays unresolved with its candidate description; no invented target.
        var service = tools.getSymbol(SERVICE, 50, null);
        assertThat(service.outgoing()).anyMatch(edge -> edge.type().equals("INJECTS") && edge.targetId() == null
                && edge.resolutionState().equals("UNRESOLVED") && edge.targetDescription().contains(DAO));
    }

    @Test
    void invalidStableIdIsReportedAndNeverInventsASymbol() {
        publish(McpTestFixture.standard());
        var detail = tools.getSymbol("java:type:demo.DoesNotExist", 50, null);
        assertThat(detail.error()).isEqualTo(NOT_FOUND);
        assertThat(detail.symbol()).isNull();
        assertThat(detail.freshness()).isNull();
        var usages = tools.findUsages("java:type:demo.DoesNotExist", 50, null);
        assertThat(usages.error()).isEqualTo(NOT_FOUND);
        assertThat(usages.usages()).isEmpty();
    }

    @Test
    void noActiveScanIsReportedConsistentlyWithoutPartialData() {
        publish(McpTestFixture.standard());
        clearActiveScan();
        var search = tools.searchSymbols("Customer", 50, null);
        assertThat(search.freshness()).isNull();
        assertThat(search.error()).isNull();
        assertThat(search.symbols()).isEmpty();
        assertThat(search.truncated()).isFalse();
        assertThat(tools.listDatabaseTables(50, null).tables()).isEmpty();
        assertThat(tools.listEntryPoints(null, 50, null).entryPoints()).isEmpty();
        assertThat(tools.getSymbol(ACTION, 50, null).error()).isEqualTo(NOT_FOUND);
        assertThat(tools.findUsages(ACTION, 50, null).error()).isEqualTo(NOT_FOUND);
        assertThat(tools.traceComponent(ACTION, Direction.OUTGOING, 5, 50, 50).error()).isEqualTo(NOT_FOUND);
        assertThat(tools.findTableUsages("CUSTOMER", 5, 50, 50).error()).isEqualTo(NOT_FOUND);
        var location = tools.inspectLocation(ACTION_PATH, 3, 50);
        assertThat(location.error()).isEqualTo(NOT_FOUND);
        assertThat(location.found()).isFalse();
    }

    @Test
    void ambiguousComponentReturnsCandidatesInsteadOfGuessing() {
        var fixture = McpTestFixture.standard();
        fixture.symbol("one:Shared", "CLASS", "demo.Shared", ACTION_PATH, 30, 32, "RESOLVED", null);
        fixture.symbol("two:Shared", "CLASS", "demo.other.Shared", ACTION_PATH, 34, 36, "RESOLVED", null);
        publish(fixture);
        var answer = tools.traceComponent("Shared", Direction.OUTGOING, 5, 50, 20);
        assertThat(answer.error()).isNull();
        assertThat(answer.selection()).isEqualTo("AMBIGUOUS");
        assertThat(answer.candidates()).extracting(CandidateView::stableId).containsExactly("one:Shared", "two:Shared");
        assertThat(answer.traversal()).isNull();
        assertThat(answer.truncated()).isFalse();
    }

    @Test
    void traversalBoundsAndTruncationAreExplicit() {
        publish(McpTestFixture.standard());
        var depthBound = tools.traceComponent(DAO, Direction.OUTGOING, 0, 100, 50);
        assertThat(depthBound.traversal().truncated()).isTrue();
        assertThat(depthBound.traversal().truncationReasons()).contains("DEPTH_LIMIT");
        assertThat(depthBound.truncated()).isTrue();
        assertThat(depthBound.traversal().maxDepth()).isZero();

        var resultBound = tools.traceComponent(ACTION, Direction.OUTGOING, 12, 1, 50);
        assertThat(resultBound.traversal().paths()).hasSize(1);
        assertThat(resultBound.traversal().truncated()).isTrue();
        assertThat(resultBound.traversal().truncationReasons()).contains("RESULT_LIMIT");
        assertThat(resultBound.traversal().resultLimit()).isEqualTo(1);
        assertThat(resultBound.traversal().paths().getFirst().evidence()).allSatisfy(edge -> {
            assertThat(edge.sourcePath()).isNotBlank();
            assertThat(edge.line()).isPositive();
        });
    }

    @Test
    void invalidBoundsCursorsAndPathsAreRejectedDeterministically() {
        publish(McpTestFixture.standard());
        assertThat(tools.searchSymbols("Customer", 0, null).error()).isEqualTo(INVALID_ARGUMENTS);
        assertThat(tools.searchSymbols("Customer", 201, null).error()).isEqualTo(INVALID_ARGUMENTS);
        assertThat(tools.searchSymbols("Customer", 50, "abc").error()).isEqualTo(INVALID_CURSOR);
        assertThat(tools.searchSymbols("Customer", 50, "-1").error()).isEqualTo(INVALID_CURSOR);
        assertThat(tools.getSymbol(ACTION, 50, "99999999999").error()).isEqualTo(INVALID_CURSOR);
        assertThat(tools.traceComponent(ACTION, Direction.OUTGOING, 17, 50, 50).error()).isEqualTo(INVALID_ARGUMENTS);
        assertThat(tools.traceComponent(ACTION, Direction.OUTGOING, 5, 501, 50).error()).isEqualTo(INVALID_ARGUMENTS);
        assertThat(tools.traceComponent("  ", Direction.OUTGOING, 5, 50, 50).error()).isEqualTo(INVALID_ARGUMENTS);
        assertThat(tools.findTableUsages("CUSTOMER", 5, 50, 0).error()).isEqualTo(INVALID_ARGUMENTS);
        assertThat(tools.inspectLocation(ACTION_PATH, 0, 50).error()).isEqualTo(INVALID_LINE);
        assertThat(tools.inspectLocation(ACTION_PATH, -3, 50).error()).isEqualTo(INVALID_LINE);
        assertThat(tools.inspectLocation("/etc/passwd", 1, 50).error()).isEqualTo(INVALID_PATH);
        assertThat(tools.inspectLocation("../outside.java", 1, 50).error()).isEqualTo(INVALID_PATH);
    }

    @Test
    void listDatabaseTablesAndTableUsagesKeepAccessAndConfidence() {
        publish(McpTestFixture.standard());
        var first = tools.listDatabaseTables(1, null);
        assertThat(first.error()).isNull();
        assertThat(first.totalCount()).isEqualTo(2);
        assertThat(first.returnedCount()).isEqualTo(1);
        assertThat(first.truncated()).isTrue();
        assertThat(first.nextCursor()).isEqualTo("1");
        assertThat(first.tables()).singleElement().satisfies(table -> assertThat(table.kind()).isEqualTo("DATABASE_TABLE"));
        var second = tools.listDatabaseTables(1, first.nextCursor());
        assertThat(second.truncated()).isFalse();
        assertThat(second.tables()).extracting(SymbolView::stableId).doesNotContain(first.tables().getFirst().stableId());

        var usages = tools.findTableUsages("CUSTOMER", 12, 50, 50);
        assertThat(usages.error()).isNull();
        assertThat(usages.selection()).isEqualTo("SELECTED");
        assertThat(usages.tables()).isNotEmpty();
        assertThat(usages.tables()).anyMatch(impact -> impact.tableId().equals(TABLE) && impact.access().equals("READ"));
        assertThat(usages.tables()).allSatisfy(impact -> {
            assertThat(impact.path().resolutionState()).isIn("RESOLVED", "INFERRED", "UNRESOLVED");
            assertThat(impact.path().evidence()).isNotEmpty();
        });
    }

    @Test
    void listEntryPointsFiltersExactPathAndCarriesFreshness() {
        UUID scan = publish(McpTestFixture.standard());
        var entries = tools.listEntryPoints("", 50, null);
        assertThat(entries.error()).isNull();
        assertThat(entries.freshness().scanId()).isEqualTo(scan.toString());
        assertThat(entries.entryPoints()).singleElement().satisfies(entry -> {
            assertThat(entry.stableId()).isEqualTo(ROUTE);
            assertThat(entry.path()).isEqualTo(ROUTE);
            assertThat(entry.sourcePath()).isEqualTo(ROUTE_PATH);
            assertThat(entry.line()).isPositive();
        });
        assertThat(tools.listEntryPoints("/missing", 50, null).entryPoints()).isEmpty();
        assertThat(tools.listEntryPoints("x".repeat(2001), 50, null).error()).isEqualTo(INVALID_ARGUMENTS);
    }

    @Test
    void inspectLocationReturnsInnermostSymbolRelationshipsAndEvidence() {
        UUID scan = publish(McpTestFixture.standard());
        var outer = tools.inspectLocation(ACTION_PATH, 3, 50);
        assertThat(outer.error()).isNull();
        assertThat(outer.found()).isTrue();
        assertThat(outer.freshness().scanId()).isEqualTo(scan.toString());
        assertThat(outer.symbol().stableId()).isEqualTo(ACTION);
        assertThat(outer.enclosingSymbols()).extracting(SymbolView::stableId).containsExactly(ACTION);
        assertThat(outer.outgoing()).isNotEmpty();
        assertThat(outer.incoming()).extracting(RelationshipView::type).contains("ROUTES_TO");
        assertThat(outer.outgoing()).allSatisfy(edge -> {
            assertThat(edge.sourcePath()).isEqualTo(ACTION_PATH);
            assertThat(edge.line()).isPositive();
        });

        var inner = tools.inspectLocation(ACTION_PATH, 15, 50);
        assertThat(inner.symbol().stableId()).isEqualTo(EXECUTE);
        assertThat(inner.enclosingSymbols()).extracting(SymbolView::stableId).containsExactly(EXECUTE, ACTION);
        assertThat(inner.outgoing()).extracting(RelationshipView::type).contains("CALLS");

        var outsideSymbol = tools.inspectLocation(ACTION_PATH, 999, 50);
        assertThat(outsideSymbol.error()).isNull();
        assertThat(outsideSymbol.found()).isFalse();
        var missingFile = tools.inspectLocation("src/demo/Unknown.java", 1, 50);
        assertThat(missingFile.found()).isFalse();
        assertThat(missingFile.error()).isNull();
        assertThat(missingFile.freshness().scanId()).isEqualTo(scan.toString());
    }

    @Test
    void everyResponseIsBoundedAndCarriesExplicitCounts() {
        publish(McpTestFixture.standard());
        assertThat(tools.searchSymbols("Customer", 50, null).limit()).isEqualTo(50);
        assertThat(tools.listDatabaseTables(null, null).limit()).isEqualTo(DEFAULT_LIMIT);
        assertThat(tools.traceComponent(ACTION, Direction.OUTGOING, null, null, null).traversal().resultLimit())
                .isEqualTo(DEFAULT_RESULT_LIMIT);
        assertThat(tools.traceComponent(ACTION, Direction.OUTGOING, null, null, null).traversal().fanOut())
                .isEqualTo(DEFAULT_FAN_OUT);
        List<String> serialized = List.of(
                mapper.writeValueAsString(tools.searchSymbols("Customer", 50, null)),
                mapper.writeValueAsString(tools.getSymbol(ACTION, 50, null)),
                mapper.writeValueAsString(tools.findUsages(ACTION, 50, null)),
                mapper.writeValueAsString(tools.traceComponent(ACTION, Direction.OUTGOING, 12, 100, 50)),
                mapper.writeValueAsString(tools.listDatabaseTables(50, null)),
                mapper.writeValueAsString(tools.findTableUsages("CUSTOMER", 12, 100, 50)),
                mapper.writeValueAsString(tools.inspectLocation(ACTION_PATH, 3, 50)),
                mapper.writeValueAsString(tools.listEntryPoints("", 50, null)));
        assertThat(serialized).allSatisfy(json -> {
            assertThat(json).contains("\"truncated\":");
            assertThat(json).doesNotContain("public class").doesNotContain("SELECT * FROM");
        });
    }
}
