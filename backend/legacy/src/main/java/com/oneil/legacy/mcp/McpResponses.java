package com.oneil.legacy.mcp;

import com.oneil.legacy.framework.FrameworkQueries;
import com.oneil.legacy.scan.ScanModel.Page;
import com.oneil.legacy.symbol.JavaIndexModel.Relationship;
import com.oneil.legacy.symbol.JavaIndexModel.Symbol;
import com.oneil.legacy.symbol.SymbolStore;
import com.oneil.legacy.traversal.TraversalEngine;
import com.oneil.legacy.traversal.TraversalEngine.Path;
import com.oneil.legacy.traversal.TraversalQueries;
import java.time.Instant;
import java.util.List;

/**
 * Presentation-only MCP payloads. These adapter views re-shape service results and add bound/truncation
 * metadata; they never derive analysis facts and never expose raw source or SQL content.
 */
public final class McpResponses {
    private McpResponses() {}

    /** Stable, serialization-friendly freshness block. */
    public record Freshness(String scanId, String gitCommitSha, String analyzerVersion, String scanCompletedAt) {
        static Freshness of(SymbolStore.Freshness freshness) {
            if (freshness == null) return null;
            Instant completed = freshness.scanCompletedAt();
            return new Freshness(freshness.scanId() == null ? null : freshness.scanId().toString(),
                    freshness.gitCommitSha(), freshness.analyzerVersion(), completed == null ? null : completed.toString());
        }
    }

    public record SymbolView(String stableId, String kind, String simpleName, String qualifiedName, String signature,
                             String resolutionState, String sourcePath, int startLine, int startColumn,
                             int endLine, int endColumn) {
        static SymbolView of(Symbol symbol) {
            return new SymbolView(symbol.stableId(), symbol.kind(), symbol.simpleName(), symbol.qualifiedName(),
                    symbol.signature(), symbol.resolutionState(), symbol.sourcePath(), symbol.startLine(),
                    symbol.startColumn(), symbol.endLine(), symbol.endColumn());
        }
    }

    public record RelationshipView(String sourceId, String targetId, String targetDescription, String type,
                                   String resolutionState, String sourcePath, int line, int column, String evidenceType) {
        static RelationshipView of(Relationship edge) {
            return new RelationshipView(edge.sourceId(), edge.targetId(), edge.targetDescription(), edge.type(),
                    edge.resolutionState(), edge.sourcePath(), edge.line(), edge.column(), edge.evidenceType());
        }
    }

    public record CandidateView(String stableId, String kind, String simpleName, String qualifiedName, String resolutionState) {
        static CandidateView of(TraversalQueries.Candidate candidate) {
            return new CandidateView(candidate.stableId(), candidate.kind(), candidate.simpleName(),
                    candidate.qualifiedName(), candidate.resolutionState());
        }
    }

    public record PathView(List<String> nodes, List<RelationshipView> evidence, String resolutionState, String termination) {
        static PathView of(Path path) {
            return new PathView(path.nodes(), path.evidence().stream().map(RelationshipView::of).toList(),
                    path.resolutionState(), path.termination());
        }
    }

    public record TraversalView(List<PathView> paths, List<PathView> frontiers, boolean truncated,
                                List<String> truncationReasons, int expandedNodes, int examinedEdges, int workLimit,
                                int maxDepth, int resultLimit, int fanOut) {
        static TraversalView of(TraversalEngine.Result result) {
            if (result == null) return null;
            return new TraversalView(result.paths().stream().map(PathView::of).toList(),
                    result.frontiers().stream().map(PathView::of).toList(), result.truncated(),
                    result.truncationReasons(), result.expandedNodes(), result.examinedEdges(), result.workLimit(),
                    result.bounds().depth(), result.bounds().limit(), result.bounds().fanOut());
        }
    }

    public record TableImpactView(String tableId, String componentId, String access, boolean direct, PathView path) {
        static TableImpactView of(TraversalQueries.TableImpact impact) {
            return new TableImpactView(impact.tableId(), impact.componentId(), impact.access(), impact.direct(),
                    PathView.of(impact.path()));
        }
    }

    public record EntryPointView(String stableId, String path, String sourcePath, int line, String dispatchParameter) {
        static EntryPointView of(FrameworkQueries.EntryPoint entry) {
            return new EntryPointView(entry.stableId(), entry.path(), entry.sourcePath(), entry.line(),
                    entry.dispatchParameter());
        }
    }

    public record SearchSymbolsResponse(Freshness freshness, String error, String query, int limit, String cursor,
                                        String nextCursor, int returnedCount, long totalCount, boolean truncated,
                                        List<SymbolView> symbols) {}

    public record GetSymbolResponse(Freshness freshness, String error, String stableId, SymbolView symbol,
                                    int limit, String cursor, String nextCursor, int returnedCount, long totalCount,
                                    boolean truncated, List<RelationshipView> outgoing) {}

    public record FindUsagesResponse(Freshness freshness, String error, String stableId, int limit, String cursor,
                                     String nextCursor, int returnedCount, long totalCount, boolean truncated,
                                     List<RelationshipView> usages) {}

    /** Shared bounded shape for trace_component and find_table_usages. */
    public record TraversalResponse(Freshness freshness, String error, String mode, String component, String direction,
                                    String selection, List<CandidateView> candidates, boolean candidatesTruncated,
                                    TraversalView traversal, List<TableImpactView> tables, boolean tablesTruncated,
                                    List<CandidateView> frontierSymbols, boolean truncated) {}

    public record ListDatabaseTablesResponse(Freshness freshness, String error, int limit, String cursor,
                                             String nextCursor, int returnedCount, long totalCount, boolean truncated,
                                             List<SymbolView> tables) {}

    public record InspectLocationResponse(Freshness freshness, String error, String path, int line, boolean found,
                                          SymbolView symbol, List<SymbolView> enclosingSymbols, boolean enclosingTruncated,
                                          List<RelationshipView> outgoing, boolean outgoingTruncated,
                                          List<RelationshipView> incoming, boolean incomingTruncated, boolean truncated) {}

    public record ListEntryPointsResponse(Freshness freshness, String error, String path, int limit, String cursor,
                                          String nextCursor, int returnedCount, long totalCount, boolean truncated,
                                          List<EntryPointView> entryPoints) {}

    static String nextCursor(Page<?> page) {
        return page.truncated() ? Integer.toString(page.offset() + page.items().size()) : null;
    }

    static List<SymbolView> symbols(List<Symbol> symbols) {
        return symbols.stream().map(SymbolView::of).toList();
    }

    static List<RelationshipView> relationships(List<Relationship> edges) {
        return edges.stream().map(RelationshipView::of).toList();
    }
}
