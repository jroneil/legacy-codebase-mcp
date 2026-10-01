package com.oneil.legacy.traversal;

import com.oneil.legacy.symbol.JavaIndexModel.Relationship;
import com.oneil.legacy.symbol.SymbolStore.Freshness;
import com.oneil.legacy.symbol.SymbolStore.SymbolNotFoundException;
import static com.oneil.legacy.traversal.TraversalEngine.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class TraversalQueries {
    private final JdbcTemplate jdbc;
    private final TraversalEngine engine = new TraversalEngine();
    public TraversalQueries(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public enum Mode { TRACE, DATABASE_TABLES, TABLE_USAGES }
    public record Candidate(String stableId, String kind, String simpleName, String qualifiedName, String resolutionState) {}
    public record TableImpact(String tableId, String componentId, String access, boolean direct, Path path) {}
    public record Answer(Freshness freshness, String selection, List<Candidate> candidates, boolean candidatesTruncated,
                         Direction direction, Result traversal, List<TableImpact> tables, List<Candidate> frontierSymbols) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Answer query(String component, Mode mode, Direction direction, Bounds bounds) {
        if (component == null || component.isBlank() || component.length() > 4000 || mode == null || direction == null || bounds == null)
            throw new IllegalArgumentException("Invalid traversal query");
        if (mode == Mode.DATABASE_TABLES) direction = Direction.OUTGOING;
        if (mode == Mode.TABLE_USAGES) direction = Direction.INCOMING;
        var freshness = active();
        if (freshness == null) throw new SymbolNotFoundException();
        var candidates = candidates(freshness.scanId(), component, mode == Mode.TABLE_USAGES);
        if (candidates.isEmpty()) throw new SymbolNotFoundException();
        boolean more = candidates.size() > 100;
        candidates = List.copyOf(candidates.subList(0, Math.min(100, candidates.size())));
        if (more || candidates.size() != 1)
            return new Answer(freshness, "AMBIGUOUS", candidates, more, direction, null, List.of(), List.of());
        String id = candidates.getFirst().stableId();
        final Direction walking = direction;
        Result result = engine.walk(id, direction, bounds,
                (node, dir, limit) -> edges(freshness.scanId(), node, dir, mode, limit), path -> {
                    if (mode == Mode.TRACE) return true;
                    var edge = walking == Direction.OUTGOING ? path.evidence().getLast() : path.evidence().getFirst();
                    return tableEdge(edge) && edge.targetId() != null && !path.termination().equals("CYCLE");
                });
        var tables = new ArrayList<TableImpact>();
        if (mode != Mode.TRACE) for (var path : result.paths()) {
            var edge = direction == Direction.OUTGOING ? path.evidence().getLast() : path.evidence().getFirst();
            tables.add(new TableImpact(edge.targetId(), direction == Direction.OUTGOING ? id : path.end(),
                    switch (edge.type()) { case "READS_TABLE" -> "READ"; case "WRITES_TABLE" -> "WRITE"; default -> "MAPPING"; },
                    path.evidence().size() == 1, path));
        }
        // Expose degraded query artifacts at dead ends without returning raw SQL/configuration snippets.
        var frontierSymbols = new ArrayList<Candidate>();
        for (String node : result.frontiers().stream().map(Path::end).distinct().sorted().toList())
            frontierSymbols.addAll(candidates(freshness.scanId(), node, false));
        return new Answer(freshness, "SELECTED", candidates, false, direction, result, List.copyOf(tables), List.copyOf(frontierSymbols));
    }
    private static boolean tableEdge(Relationship edge) {
        return Set.of("READS_TABLE", "WRITES_TABLE", "MAPS_TO_TABLE").contains(edge.type());
    }
    private List<Candidate> candidates(UUID scan, String name, boolean tableOnly) {
        String kind = tableOnly ? " AND kind='DATABASE_TABLE'" : "";
        var exact = jdbc.query("SELECT stable_id,kind,simple_name,qualified_name,resolution_state FROM java_symbol WHERE scan_id=? AND stable_id=?" + kind,
                (rs, row) -> new Candidate(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), scan, name);
        if (!exact.isEmpty()) return exact;
        return jdbc.query("SELECT stable_id,kind,simple_name,qualified_name,resolution_state FROM java_symbol WHERE scan_id=? AND (simple_name=? OR qualified_name=?)"
                        + kind + " ORDER BY stable_id COLLATE \"C\" LIMIT 101",
                (rs, row) -> new Candidate(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), scan, name, name);
    }
    private List<Relationship> edges(UUID scan, String node, Direction direction, Mode mode, int limit) {
        String filter = mode == Mode.TRACE ? "" : """
                 AND relationship_type IN ('CONTAINS','CALLS','ROUTES_TO','FORWARDS_TO','INJECTS','WIRES_TO',
                                            'DECLARES_QUERY','EXECUTES_QUERY','READS_TABLE','WRITES_TABLE','MAPS_TO_TABLE')
                 AND NOT (relationship_type='WIRES_TO' AND coalesce(target_description,'') LIKE 'binding=%')
                """;
        // Scoped interface bindings are not global dispatch edges. Bean INJECTS -> WIRES_TO retains XML context.
        return jdbc.query("SELECT * FROM java_relationship WHERE scan_id=? AND "
                        + (direction == Direction.OUTGOING ? "source_id=?" : "target_id=?") + filter
                        + " ORDER BY source_id COLLATE \"C\", target_id COLLATE \"C\" NULLS LAST, relationship_type, source_path COLLATE \"C\", line, column_number, id LIMIT ?",
                (rs, row) -> new Relationship(rs.getString("source_id"), rs.getString("target_id"), rs.getString("target_description"),
                        rs.getString("relationship_type"), rs.getString("resolution_state"), rs.getString("source_path"),
                        rs.getInt("line"), rs.getInt("column_number"), rs.getString("evidence_type")), scan, node, limit);
    }
    private Freshness active() {
        var matches = jdbc.query("""
                SELECT s.id,s.git_commit_sha,s.analyzer_version,s.completed_at FROM active_scan a
                JOIN scan s ON a.scan_id=s.id WHERE a.singleton=true AND s.status='COMPLETED'
                """, (rs, row) -> new Freshness(rs.getObject("id", UUID.class), rs.getString("git_commit_sha"),
                rs.getString("analyzer_version"), rs.getTimestamp("completed_at").toInstant()));
        return matches.isEmpty() ? null : matches.getFirst();
    }
}
