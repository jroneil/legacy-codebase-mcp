package com.oneil.legacy.traversal;

import com.oneil.legacy.symbol.JavaIndexModel.Relationship;
import java.util.*;
import java.util.function.Predicate;

/** Bounded breadth-first enumeration of evidence paths; never guesses unresolved targets. */
public final class TraversalEngine {
    public enum Direction { OUTGOING, INCOMING }
    public record Bounds(int depth, int limit, int fanOut) {
        public Bounds {
            if (depth < 0 || depth > 16 || limit < 1 || limit > 500 || fanOut < 1 || fanOut > 200)
                throw new IllegalArgumentException("Invalid traversal bounds");
        }
    }
    public static final int MAX_WORK = 5000;
    /** Implementations return at most limit rows in deterministic order, scoped to one snapshot. */
    public interface Graph { List<Relationship> edges(String node, Direction direction, int limit); }
    public record Path(List<String> nodes, List<Relationship> evidence, String resolutionState, String termination) {
        public Path { nodes = List.copyOf(nodes); evidence = List.copyOf(evidence); }
        public String end() { return nodes.getLast(); }
    }
    public record Result(List<Path> paths, List<Path> frontiers, boolean truncated, List<String> truncationReasons,
                         int expandedNodes, int examinedEdges, int workLimit, Bounds bounds) {}

    public Result walk(String start, Direction direction, Bounds bounds, Graph graph, Predicate<Path> select) {
        var queue = new ArrayDeque<Path>();
        queue.add(new Path(List.of(start), List.of(), "RESOLVED", "START"));
        var paths = new ArrayList<Path>();
        var frontiers = new ArrayList<Path>();
        var reasons = new TreeSet<String>();
        int work = 0, expanded = 0, examined = 0;
        outer: while (!queue.isEmpty()) {
            if (work >= MAX_WORK - 2) { reasons.add("WORK_LIMIT"); break; }
            var path = queue.removeFirst();
            int cap = Math.min(bounds.fanOut(), MAX_WORK - work - 2);
            var rows = graph.edges(path.end(), direction, cap + 1);
            expanded++;
            work += 1 + rows.size(); // Includes the look-ahead row and the adjacency query itself.
            if (rows.isEmpty()) {
                frontier(frontiers, path, "LEAF", bounds.limit(), reasons);
                continue;
            }
            if (path.evidence().size() == bounds.depth()) {
                reasons.add("DEPTH_LIMIT");
                frontier(frontiers, path, "DEPTH_LIMIT", bounds.limit(), reasons);
                continue;
            }
            if (rows.size() > cap) reasons.add(cap == bounds.fanOut() ? "FAN_OUT_LIMIT" : "WORK_LIMIT");
            for (var edge : rows.subList(0, Math.min(cap, rows.size()))) {
                examined++;
                String next = direction == Direction.OUTGOING ? edge.targetId() : edge.sourceId();
                boolean cycle = next != null && path.nodes().contains(next);
                String end = next == null ? (edge.resolutionState().equals("UNRESOLVED") ? "UNRESOLVED" : "EXTERNAL")
                        : cycle ? "CYCLE" : "STEP";
                var nodes = new ArrayList<>(path.nodes());
                if (next != null) nodes.add(next);
                var evidence = new ArrayList<>(path.evidence()); evidence.add(edge);
                var child = new Path(nodes, evidence, weakest(path.resolutionState(), edge.resolutionState()), end);
                if (select.test(child)) {
                    if (paths.size() == bounds.limit()) { reasons.add("RESULT_LIMIT"); break outer; }
                    paths.add(child);
                }
                if (next == null || cycle) frontier(frontiers, child, end, bounds.limit(), reasons);
                else queue.addLast(child);
            }
        }
        return new Result(List.copyOf(paths), List.copyOf(frontiers), !reasons.isEmpty(), List.copyOf(reasons),
                expanded, examined, MAX_WORK, bounds);
    }
    private static void frontier(List<Path> out, Path path, String termination, int limit, Set<String> reasons) {
        if (out.size() == limit) { reasons.add("FRONTIER_LIMIT"); return; }
        out.add(new Path(path.nodes(), path.evidence(), path.resolutionState(), termination));
    }
    public static String weakest(String left, String right) {
        if (left.equals("UNRESOLVED") || right.equals("UNRESOLVED")) return "UNRESOLVED";
        if (left.equals("INFERRED") || right.equals("INFERRED")) return "INFERRED";
        return "RESOLVED";
    }
}
