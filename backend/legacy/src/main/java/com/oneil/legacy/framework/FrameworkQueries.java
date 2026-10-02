package com.oneil.legacy.framework;

import com.oneil.legacy.scan.ScanModel.Page;
import com.oneil.legacy.symbol.JavaIndexModel.Relationship;
import com.oneil.legacy.symbol.SymbolStore.Freshness;
import com.oneil.legacy.symbol.SymbolStore.SymbolNotFoundException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** A bounded configuration-wiring trace, not a general call-graph or runtime execution trace. */
@Service
public class FrameworkQueries {
    private final JdbcTemplate jdbc;
    public FrameworkQueries(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record EntryPoint(String stableId, String path, String sourcePath, int line, String dispatchParameter) {}
    public record Entries(Freshness freshness, Page<EntryPoint> candidates) {}
    public record TracePath(List<String> components, List<Relationship> evidence, String resolutionState, String termination) {}
    public record Trace(Freshness freshness, String entryId, List<TracePath> paths, boolean truncated) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Entries entries(String path, int limit, int offset) {
        var active = active();
        if (active == null) return new Entries(null, new Page<>(List.of(), 0, offset, limit, false));
        String where = "scan_id=? AND kind='ROUTE' AND (?='' OR qualified_name=?)";
        long count = jdbc.queryForObject("SELECT count(*) FROM java_symbol WHERE " + where, Long.class, active.scanId(), path, path);
        var entries = jdbc.query("SELECT * FROM java_symbol WHERE " + where + " ORDER BY stable_id LIMIT ? OFFSET ?", (rs, row) ->
                new EntryPoint(rs.getString("stable_id"), rs.getString("qualified_name"), rs.getString("source_path"),
                        rs.getInt("start_line"), rs.getString("signature")), active.scanId(), path, path, limit, offset);
        return new Entries(active, new Page<>(entries, count, offset, limit, offset + (long) entries.size() < count));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Trace trace(String entryId, int depth, int limit) {
        var active = active();
        if (active == null) throw new SymbolNotFoundException();
        var entry = jdbc.queryForList("SELECT qualified_name FROM java_symbol WHERE scan_id=? AND stable_id=? AND kind='ROUTE'",
                String.class, active.scanId(), entryId);
        if (entry.isEmpty()) throw new SymbolNotFoundException();
        var walk = new Walk(active.scanId(), depth, limit);
        var routeEdges = walk.edges(entryId, "ROUTES_TO");
        if (routeEdges.isEmpty()) walk.add(List.of(entry.getFirst()), List.of(), "NO_ACTION_CLASS");
        for (var route : routeEdges) {
            if (route.targetId() != null && route.targetId().startsWith("java:method:")) {
                walk.add(List.of(entry.getFirst(), route.targetId()), List.of(route), "CONTROLLER_METHOD");
                continue;
            }
            var evidence = List.of(route);
            var components = append(List.of(entry.getFirst()), route.targetId() == null ? route.targetDescription() : route.targetId());
            if (route.targetId() == null) { walk.add(components, evidence, "UNRESOLVED"); continue; }
            var bridges = walk.edges(route.targetId(), "WIRES_TO").stream()
                    .filter(e -> e.targetId() == null || e.targetId().startsWith("spring:bean:")).toList();
            if (bridges.isEmpty()) walk.add(components, evidence, "NO_SPRING_BINDING");
            for (var bridge : bridges) {
                if (bridge.targetId() == null) walk.add(components, append(evidence, bridge), "UNRESOLVED");
                else walk.bean(bridge.targetId(), components, append(evidence, bridge), new HashSet<>(), 0, false);
            }
        }
        return new Trace(active, entryId, List.copyOf(walk.paths), walk.truncated);
    }
    private final class Walk {
        final UUID scan;
        final int maxDepth, limit;
        final List<TracePath> paths = new ArrayList<>();
        int remainingEdges = 1000;
        boolean truncated;
        Walk(UUID scan, int depth, int limit) { this.scan = scan; this.maxDepth = depth; this.limit = limit; }
        List<Relationship> edges(String source, String type) {
            if (remainingEdges <= 0 || paths.size() >= limit) { truncated = true; return List.of(); }
            int cap = Math.min(remainingEdges, 200);
            var rows = jdbc.query("""
                    SELECT * FROM java_relationship WHERE scan_id=? AND source_id=? AND relationship_type=?
                    ORDER BY source_path, line, column_number, id LIMIT ?
                    """, (rs, row) -> new Relationship(rs.getString("source_id"), rs.getString("target_id"), rs.getString("target_description"),
                    rs.getString("relationship_type"), rs.getString("resolution_state"), rs.getString("source_path"), rs.getInt("line"),
                    rs.getInt("column_number"), rs.getString("evidence_type")), scan, source, type, cap + 1);
            if (rows.size() > cap) { truncated = true; rows = rows.subList(0, cap); }
            remainingEdges -= rows.size();
            return rows;
        }
        void bean(String bean, List<String> components, List<Relationship> evidence, Set<String> visited, int depth, boolean includeClass) {
            if (paths.size() >= limit) { truncated = true; return; }
            if (!visited.add(bean)) { add(components, evidence, "CYCLE"); return; }
            if (includeClass) {
                var implementations = edges(bean, "WIRES_TO");
                if (implementations.size() != 1 || implementations.getFirst().targetId() == null) {
                    add(components, concat(evidence, implementations), "UNRESOLVED"); return;
                }
                var implementation = implementations.getFirst();
                components = append(components, implementation.targetId());
                evidence = append(evidence, implementation);
            }
            var injections = edges(bean, "INJECTS");
            if (injections.isEmpty()) { add(components, evidence, truncated ? "BOUND" : "LEAF"); return; }
            if (depth >= maxDepth) { truncated = true; add(components, evidence, "DEPTH_BOUND"); return; }
            for (var injection : injections) {
                if (injection.targetId() == null) add(components, append(evidence, injection), "UNRESOLVED");
                else bean(injection.targetId(), components, append(evidence, injection), new HashSet<>(visited), depth + 1, true);
            }
        }
        void add(List<String> components, List<Relationship> evidence, String termination) {
            if (paths.size() >= limit) { truncated = true; return; }
            String state = evidence.stream().anyMatch(e -> e.resolutionState().equals("UNRESOLVED")) || termination.equals("UNRESOLVED") ? "UNRESOLVED"
                    : evidence.stream().anyMatch(e -> e.resolutionState().equals("INFERRED")) ? "INFERRED" : "RESOLVED";
            paths.add(new TracePath(List.copyOf(components), List.copyOf(evidence), state, termination));
        }
    }
    private Freshness active() {
        var matches = jdbc.query("""
                SELECT s.id,s.git_commit_sha,s.analyzer_version,s.completed_at FROM active_scan a
                JOIN scan s ON a.scan_id=s.id WHERE a.singleton=true AND s.status='COMPLETED'
                """, (rs, row) -> new Freshness(rs.getObject("id", UUID.class), rs.getString("git_commit_sha"),
                rs.getString("analyzer_version"), rs.getTimestamp("completed_at").toInstant()));
        return matches.isEmpty() ? null : matches.getFirst();
    }
    private static <T> List<T> append(List<T> values, T value) { var result = new ArrayList<>(values); result.add(value); return result; }
    private static <T> List<T> concat(List<T> values, List<T> more) { var result = new ArrayList<>(values); result.addAll(more); return result; }
}
