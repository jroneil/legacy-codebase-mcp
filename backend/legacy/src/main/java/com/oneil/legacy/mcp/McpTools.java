package com.oneil.legacy.mcp;

import static com.oneil.legacy.mcp.McpResponses.*;
import static com.oneil.legacy.traversal.TraversalEngine.Bounds;
import static com.oneil.legacy.traversal.TraversalEngine.Direction;
import static com.oneil.legacy.traversal.TraversalQueries.Mode;

import com.oneil.legacy.framework.FrameworkQueries;
import com.oneil.legacy.scan.ScanModel.Page;
import com.oneil.legacy.symbol.JavaIndexModel.Relationship;
import com.oneil.legacy.symbol.JavaIndexModel.Symbol;
import com.oneil.legacy.symbol.SymbolStore;
import com.oneil.legacy.traversal.TraversalQueries;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Slice 6 MCP interface.
 *
 * <p>Every handler is an adapter: it validates bounds, calls the same application/query service used by the
 * REST controllers, and maps the result into a bounded payload. No analysis, traversal, confidence,
 * ambiguity, freshness or persistence logic is implemented here.
 */
@Component
public class McpTools {
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;
    public static final int MAX_OFFSET = 1_000_000;
    public static final int DEFAULT_DEPTH = 12;
    public static final int MAX_DEPTH = 16;
    public static final int DEFAULT_RESULT_LIMIT = 100;
    public static final int MAX_RESULT_LIMIT = 500;
    public static final int DEFAULT_FAN_OUT = 50;
    public static final int MAX_FAN_OUT = 200;
    public static final String NOT_FOUND = "Symbol not found in an active completed scan.";
    public static final String INVALID_ARGUMENTS = "Invalid arguments or bounds.";
    public static final String INVALID_CURSOR = "Invalid cursor.";
    public static final String INVALID_LINE = "Invalid line: must be >= 1.";
    public static final String INVALID_PATH = "Invalid path: must be a repository-relative path.";

    private final SymbolStore store;
    private final TraversalQueries traversal;
    private final FrameworkQueries framework;

    public McpTools(SymbolStore store, TraversalQueries traversal, FrameworkQueries framework) {
        this.store = store;
        this.traversal = traversal;
        this.framework = framework;
    }

    @McpTool(name = "search_symbols",
            description = "Search indexed symbols of the active completed scan by literal substring of simple or "
                    + "qualified name, or by exact stable ID. Returns bounded, deterministic candidates with scan freshness.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public SearchSymbolsResponse searchSymbols(
            @McpToolParam(description = "Literal substring to match, or an exact stable ID. Empty returns all symbols ordered by stable ID.")
            String query,
            @McpToolParam(required = false, description = "Maximum items to return (1..200, default 50).") Integer limit,
            @McpToolParam(required = false, description = "Opaque pagination cursor returned as nextCursor.") String cursor) {
        if (!validLimit(limit)) return new SearchSymbolsResponse(null, INVALID_ARGUMENTS, query, 0, cursor, null, 0, 0, false, List.of());
        int size = limit(limit);
        Integer offset = offset(cursor);
        if (offset == null) return new SearchSymbolsResponse(null, INVALID_CURSOR, query, size, cursor, null, 0, 0, false, List.of());
        String q = query == null ? "" : query.trim();
        if (q.length() > 1000) return new SearchSymbolsResponse(null, INVALID_ARGUMENTS, q, size, cursor, null, 0, 0, false, List.of());
        var result = store.search(q, size, offset);
        Page<Symbol> page = result.candidates();
        return new SearchSymbolsResponse(Freshness.of(result.freshness()), null, q, size, cursor, nextCursor(page),
                page.items().size(), page.totalCount(), page.truncated(), symbols(page.items()));
    }

    @McpTool(name = "get_symbol",
            description = "Return one indexed symbol by stable ID plus a bounded page of its outgoing relationships "
                    + "(containment, calls, framework wiring, query and table edges) with evidence locations.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public GetSymbolResponse getSymbol(
            @McpToolParam(description = "Stable symbol ID, for example java:method:com.acme.CustomerService#find(java.lang.Long)")
            String stableId,
            @McpToolParam(required = false, description = "Maximum outgoing relationships to return (1..200, default 50).") Integer limit,
            @McpToolParam(required = false, description = "Opaque pagination cursor returned as nextCursor.") String cursor) {
        if (!validLimit(limit)) return new GetSymbolResponse(null, INVALID_ARGUMENTS, stableId, null, 0, cursor, null, 0, 0, false, List.of());
        int size = limit(limit);
        Integer offset = offset(cursor);
        if (offset == null) return new GetSymbolResponse(null, INVALID_CURSOR, stableId, null, size, cursor, null, 0, 0, false, List.of());
        try {
            var detail = store.detail(stableId, size, offset);
            Page<Relationship> page = detail.outgoing();
            return new GetSymbolResponse(Freshness.of(detail.freshness()), null, stableId, SymbolView.of(detail.symbol()),
                    size, cursor, nextCursor(page), page.items().size(), page.totalCount(), page.truncated(),
                    relationships(page.items()));
        } catch (SymbolStore.SymbolNotFoundException missing) {
            return new GetSymbolResponse(null, NOT_FOUND, stableId, null, size, cursor, null, 0, 0, false, List.of());
        }
    }

    @McpTool(name = "find_usages",
            description = "Return a bounded page of incoming relationships for a stable symbol ID. Containment edges are "
                    + "excluded; each usage keeps its resolution state and evidence location.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public FindUsagesResponse findUsages(
            @McpToolParam(description = "Stable symbol ID whose incoming relationships should be returned.") String stableId,
            @McpToolParam(required = false, description = "Maximum usages to return (1..200, default 50).") Integer limit,
            @McpToolParam(required = false, description = "Opaque pagination cursor returned as nextCursor.") String cursor) {
        if (!validLimit(limit)) return new FindUsagesResponse(null, INVALID_ARGUMENTS, stableId, 0, cursor, null, 0, 0, false, List.of());
        int size = limit(limit);
        Integer offset = offset(cursor);
        if (offset == null) return new FindUsagesResponse(null, INVALID_CURSOR, stableId, size, cursor, null, 0, 0, false, List.of());
        try {
            var usages = store.usages(stableId, size, offset);
            Page<Relationship> page = usages.usages();
            return new FindUsagesResponse(Freshness.of(usages.freshness()), null, stableId, size, cursor, nextCursor(page),
                    page.items().size(), page.totalCount(), page.truncated(), relationships(page.items()));
        } catch (SymbolStore.SymbolNotFoundException missing) {
            return new FindUsagesResponse(null, NOT_FOUND, stableId, size, cursor, null, 0, 0, false, List.of());
        }
    }

    @McpTool(name = "trace_component",
            description = "Bounded outgoing or incoming relationship traversal from a component. Ambiguous names return "
                    + "candidates instead of a guess. Path resolution is the weakest edge state; truncation is explicit.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public TraversalResponse traceComponent(
            @McpToolParam(description = "Component name, path or exact stable ID to start from.") String component,
            @McpToolParam(required = false, description = "OUTGOING (default) or INCOMING.") Direction direction,
            @McpToolParam(required = false, description = "Maximum traversal depth (0..16, default 12).") Integer maxDepth,
            @McpToolParam(required = false, description = "Maximum paths to return (1..500, default 100).") Integer limit,
            @McpToolParam(required = false, description = "Maximum edges examined per node (1..200, default 50).") Integer fanOut) {
        return walk("TRACE", component, direction == null ? Direction.OUTGOING : direction, maxDepth, limit, fanOut);
    }

    @McpTool(name = "list_database_tables",
            description = "List indexed database tables of the active completed scan. Bounded and deterministic; no live "
                    + "database connection or catalogue is queried.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ListDatabaseTablesResponse listDatabaseTables(
            @McpToolParam(required = false, description = "Maximum tables to return (1..200, default 50).") Integer limit,
            @McpToolParam(required = false, description = "Opaque pagination cursor returned as nextCursor.") String cursor) {
        if (!validLimit(limit)) return new ListDatabaseTablesResponse(null, INVALID_ARGUMENTS, 0, cursor, null, 0, 0, false, List.of());
        int size = limit(limit);
        Integer offset = offset(cursor);
        if (offset == null) return new ListDatabaseTablesResponse(null, INVALID_CURSOR, size, cursor, null, 0, 0, false, List.of());
        var result = store.tables(size, offset);
        Page<Symbol> page = result.tables();
        return new ListDatabaseTablesResponse(Freshness.of(result.freshness()), null, size, cursor, nextCursor(page),
                page.items().size(), page.totalCount(), page.truncated(), symbols(page.items()));
    }

    @McpTool(name = "find_table_usages",
            description = "Bounded incoming traversal that finds components reading, writing or mapping a table. Results "
                    + "keep access kind, direct/transitive distinction and weakest-edge path confidence.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public TraversalResponse findTableUsages(
            @McpToolParam(description = "Table name or exact db:table: stable ID, for example CUSTOMER.") String table,
            @McpToolParam(required = false, description = "Maximum traversal depth (0..16, default 12).") Integer maxDepth,
            @McpToolParam(required = false, description = "Maximum paths to return (1..500, default 100).") Integer limit,
            @McpToolParam(required = false, description = "Maximum edges examined per node (1..200, default 50).") Integer fanOut) {
        return walk("TABLE_USAGES", table, Direction.INCOMING, maxDepth, limit, fanOut);
    }

    @McpTool(name = "inspect_location",
            description = "Locate the symbol containing a repository-relative file path and line, plus a bounded view of "
                    + "its outgoing and incoming indexed relationships. Returns no source file content.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public InspectLocationResponse inspectLocation(
            @McpToolParam(description = "Repository-relative file path, for example src/demo/CustomerAction.java") String path,
            @McpToolParam(description = "1-based line number within that file.") Integer line,
            @McpToolParam(required = false, description = "Maximum enclosing symbols and relationships per direction (1..200, default 50).")
            Integer limit) {
        if (!validLimit(limit)) return emptyLocation(INVALID_ARGUMENTS, path, line);
        int size = limit(limit);
        if (!relativePath(path)) return emptyLocation(INVALID_PATH, path, line);
        if (line == null || line < 1) return emptyLocation(INVALID_LINE, path, line);
        var location = store.locate(path, line, size, 0);
        List<SymbolView> enclosing = symbols(location.symbols().items());
        if (location.freshness() == null) return emptyLocation(NOT_FOUND, path, line);
        if (enclosing.isEmpty()) {
            return new InspectLocationResponse(Freshness.of(location.freshness()), null, path, line, false, null, List.of(),
                    false, List.of(), false, List.of(), false, false);
        }
        SymbolView innermost = enclosing.getFirst();
        var detail = store.detail(innermost.stableId(), size, 0);
        var usages = store.usages(innermost.stableId(), size, 0);
        boolean enclosingTruncated = location.symbols().truncated();
        return new InspectLocationResponse(Freshness.of(location.freshness()), null, path, line, true, innermost,
                enclosing, enclosingTruncated, relationships(detail.outgoing().items()), detail.outgoing().truncated(),
                relationships(usages.usages().items()), usages.usages().truncated(),
                enclosingTruncated || detail.outgoing().truncated() || usages.usages().truncated());
    }

    @McpTool(name = "list_entry_points",
            description = "List deterministic framework entry points (Struts, Spring MVC and Grails routes) of the active completed scan, "
                    + "optionally filtered by an exact route path. Bounded and read-only.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ListEntryPointsResponse listEntryPoints(
            @McpToolParam(required = false, description = "Exact route path filter, for example /customer/search. Empty lists all routes.")
            String path,
            @McpToolParam(required = false, description = "Maximum entry points to return (1..200, default 50).") Integer limit,
            @McpToolParam(required = false, description = "Opaque pagination cursor returned as nextCursor.") String cursor) {
        if (!validLimit(limit)) return new ListEntryPointsResponse(null, INVALID_ARGUMENTS, path, 0, cursor, null, 0, 0, false, List.of());
        int size = limit(limit);
        Integer offset = offset(cursor);
        if (offset == null) return new ListEntryPointsResponse(null, INVALID_CURSOR, path, size, cursor, null, 0, 0, false, List.of());
        String filter = path == null ? "" : path;
        if (filter.length() > 2000) return new ListEntryPointsResponse(null, INVALID_ARGUMENTS, filter, size, cursor, null, 0, 0, false, List.of());
        var result = framework.entries(filter, size, offset);
        Page<FrameworkQueries.EntryPoint> page = result.candidates();
        return new ListEntryPointsResponse(Freshness.of(result.freshness()), null, filter, size, cursor, nextCursor(page),
                page.items().size(), page.totalCount(), page.truncated(),
                page.items().stream().map(EntryPointView::of).toList());
    }

    private TraversalResponse walk(String mode, String component, Direction direction, Integer maxDepth, Integer limit,
                                   Integer fanOut) {
        int depth = maxDepth == null ? DEFAULT_DEPTH : maxDepth;
        int size = limit == null ? DEFAULT_RESULT_LIMIT : limit;
        int fan = fanOut == null ? DEFAULT_FAN_OUT : fanOut;
        if (component == null || component.isBlank() || component.length() > 4000) {
            return emptyTraversal(INVALID_ARGUMENTS, mode, component, direction);
        }
        if (depth < 0 || depth > MAX_DEPTH || size < 1 || size > MAX_RESULT_LIMIT || fan < 1 || fan > MAX_FAN_OUT) {
            return emptyTraversal(INVALID_ARGUMENTS, mode, component, direction);
        }
        Mode queryMode = mode.equals("TRACE") ? Mode.TRACE : Mode.TABLE_USAGES;
        try {
            var answer = traversal.query(component, queryMode, direction, new Bounds(depth, size, fan));
            boolean tablesTruncated = answer.traversal() != null && answer.traversal().truncated();
            return new TraversalResponse(Freshness.of(answer.freshness()), null, mode, component, answer.direction().name(),
                    answer.selection(), answer.candidates().stream().map(CandidateView::of).toList(),
                    answer.candidatesTruncated(), TraversalView.of(answer.traversal()),
                    answer.tables().stream().map(TableImpactView::of).toList(), tablesTruncated,
                    answer.frontierSymbols().stream().map(CandidateView::of).toList(),
                    answer.candidatesTruncated() || tablesTruncated);
        } catch (SymbolStore.SymbolNotFoundException missing) {
            return emptyTraversal(NOT_FOUND, mode, component, direction);
        } catch (IllegalArgumentException invalid) {
            return emptyTraversal(INVALID_ARGUMENTS, mode, component, direction);
        }
    }

    private static TraversalResponse emptyTraversal(String error, String mode, String component, Direction direction) {
        return new TraversalResponse(null, error, mode, component, direction.name(), null, List.of(), false, null,
                List.of(), false, List.of(), false);
    }

    private static InspectLocationResponse emptyLocation(String error, String path, Integer line) {
        return new InspectLocationResponse(null, error, path, line == null ? 0 : line, false, null, List.of(), false,
                List.of(), false, List.of(), false, false);
    }

    private static boolean validLimit(Integer limit) {
        return limit == null || limit >= 1 && limit <= MAX_LIMIT;
    }

    private static int limit(Integer limit) {
        return limit == null ? DEFAULT_LIMIT : limit;
    }

    /** Returns null when the cursor is not a valid bounded offset. */
    private static Integer offset(String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        int value;
        try {
            value = Integer.parseInt(cursor.trim());
        } catch (NumberFormatException invalid) {
            return null;
        }
        return value < 0 || value > MAX_OFFSET ? null : value;
    }

    private static boolean relativePath(String path) {
        if (path == null || path.isBlank() || path.length() > 4000) return false;
        if (path.startsWith("/") || path.startsWith("\\") || path.matches("^[A-Za-z]:.*")) return false;
        for (String segment : path.split("/", -1)) if (segment.equals("..")) return false;
        return true;
    }
}
