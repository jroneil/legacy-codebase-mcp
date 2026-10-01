package com.oneil.legacy.traversal;

import static org.assertj.core.api.Assertions.*;
import static com.oneil.legacy.traversal.TraversalEngine.*;
import com.oneil.legacy.symbol.JavaIndexModel.Relationship;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TraversalEngineTests {
    private final TraversalEngine engine = new TraversalEngine();
    private final Bounds bounds = new Bounds(12, 100, 50);
    static Relationship edge(String from, String to, String state) {
        return new Relationship(from, to, to == null ? "candidates: UnknownA, UnknownB" : null,
                "CALLS", state, "Fixture.java", 10, 3, "JAVA_AST");
    }
    private Graph graph(Relationship... edges) {
        return (node, direction, limit) -> Arrays.stream(edges)
                .filter(e -> node.equals(direction == Direction.OUTGOING ? e.sourceId() : e.targetId()))
                .sorted(Comparator.comparing(Relationship::toString)).limit(limit).toList();
    }
    @Test void resolvedPathAndEvidence() {
        var ab = edge("A", "B", "RESOLVED"); var bc = edge("B", "C", "RESOLVED");
        var result = engine.walk("A", Direction.OUTGOING, bounds, graph(ab, bc), p -> true);
        assertThat(result.paths()).hasSize(2);
        assertThat(result.paths().getLast().evidence()).containsExactly(ab, bc);
        assertThat(result.paths()).allMatch(p -> p.resolutionState().equals("RESOLVED"));
        assertThat(result.truncated()).isFalse();
    }
    @Test void inferredAndMixedUnresolvedPathsKeepWeakestState() {
        var result = engine.walk("A", Direction.OUTGOING, bounds, graph(edge("A", "B", "INFERRED"),
                edge("B", "C", "RESOLVED"), edge("C", null, "UNRESOLVED")), p -> true);
        assertThat(result.paths()).extracting(Path::resolutionState).containsExactly("INFERRED", "INFERRED", "UNRESOLVED");
        assertThat(result.paths().getLast().nodes()).containsExactly("A", "B", "C");
        assertThat(result.frontiers().getFirst().termination()).isEqualTo("UNRESOLVED");
        assertThat(result.paths().getLast().evidence().getLast().targetDescription()).contains("UnknownA", "UnknownB");
    }
    @Test void confidenceCompositionAllPairs() {
        String[] states = {"RESOLVED", "INFERRED", "UNRESOLVED"};
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++)
            assertThat(weakest(states[i], states[j])).isEqualTo(states[Math.max(i, j)]);
    }
    @Test void breadthFirstKeepsShortestAndAlternateEvidencePaths() {
        var result = engine.walk("A", Direction.OUTGOING, bounds, graph(edge("A", "B", "RESOLVED"),
                edge("B", "D", "INFERRED"), edge("A", "D", "RESOLVED")), p -> p.end().equals("D"));
        assertThat(result.paths()).extracting(p -> p.nodes()).containsExactly(List.of("A", "D"), List.of("A", "B", "D"));
        assertThat(result.paths()).extracting(Path::resolutionState).containsExactly("RESOLVED", "INFERRED");
    }
    @Test void cyclesStopPerPathWithoutPruningOtherBranches() {
        var result = engine.walk("A", Direction.OUTGOING, bounds, graph(edge("A", "B", "RESOLVED"),
                edge("B", "A", "RESOLVED"), edge("A", "C", "RESOLVED"), edge("C", "B", "RESOLVED")), p -> true);
        assertThat(result.paths()).hasSize(5);
        assertThat(result.frontiers()).hasSize(2).allMatch(p -> p.termination().equals("CYCLE"));
        assertThat(result.truncated()).isFalse();
    }
    @Test void incomingRetainsStoredEdgeOrientationAndConfidence() {
        var ab = edge("A", "B", "INFERRED"); var bc = edge("B", "C", "RESOLVED");
        var result = engine.walk("C", Direction.INCOMING, bounds, graph(ab, bc), p -> true);
        assertThat(result.paths().getLast().nodes()).containsExactly("C", "B", "A");
        assertThat(result.paths().getLast().evidence()).containsExactly(bc, ab);
        assertThat(result.paths().getLast().resolutionState()).isEqualTo("INFERRED");
    }
    @Test void depthCutoffAndZeroDepthAreExplicitButLeafIsNotTruncated() {
        var graph = graph(edge("A", "B", "RESOLVED"), edge("B", "C", "RESOLVED"));
        var result = engine.walk("A", Direction.OUTGOING, new Bounds(1, 10, 10), graph, p -> true);
        assertThat(result.paths()).hasSize(1);
        assertThat(result.truncationReasons()).containsExactly("DEPTH_LIMIT");
        assertThat(result.frontiers().getFirst().nodes()).containsExactly("A", "B");
        assertThat(engine.walk("A", Direction.OUTGOING, new Bounds(0, 10, 10), graph, p -> true).truncated()).isTrue();
        assertThat(engine.walk("C", Direction.OUTGOING, new Bounds(0, 10, 10), graph, p -> true).truncated()).isFalse();
    }
    @Test void fanOutAndResultLimitsAreExplicitAndDeterministic() {
        var edges = new ArrayList<Relationship>();
        for (int i = 0; i < 1000; i++) edges.add(edge("A", String.format("B%04d", i), "RESOLVED"));
        var graph = graph(edges.toArray(Relationship[]::new));
        var result = engine.walk("A", Direction.OUTGOING, new Bounds(12, 10, 5), graph, p -> true);
        assertThat(result.paths()).hasSize(5);
        assertThat(result.truncationReasons()).contains("FAN_OUT_LIMIT");
        assertThat(engine.walk("A", Direction.OUTGOING, new Bounds(12, 10, 5), graph, p -> true)).isEqualTo(result);
        var capped = engine.walk("A", Direction.OUTGOING, new Bounds(12, 2, 5), graph, p -> true);
        assertThat(capped.paths()).hasSize(2);
        assertThat(capped.truncationReasons()).contains("RESULT_LIMIT");
    }
    @Test void exactResultLimitDoesNotFalselyClaimTruncation() {
        var result = engine.walk("A", Direction.OUTGOING, new Bounds(1, 1, 1), graph(edge("A", "B", "RESOLVED")), p -> true);
        assertThat(result.truncated()).isFalse();
    }
    @Test void totalWorkBoundAppliesEvenWhenNoResultsMatch() {
        var fetched = new AtomicInteger(); var queries = new AtomicInteger();
        Graph infiniteTree = (node, direction, limit) -> {
            queries.incrementAndGet();
            var rows = new ArrayList<Relationship>();
            for (int i = 0; i < Math.min(100, limit); i++) rows.add(edge(node, node + "/" + i, "RESOLVED"));
            fetched.addAndGet(rows.size()); return rows;
        };
        var result = engine.walk("A", Direction.OUTGOING, new Bounds(16, 500, 100), infiniteTree, p -> false);
        assertThat(result.paths()).isEmpty();
        assertThat(result.truncationReasons()).contains("WORK_LIMIT");
        assertThat(fetched.get() + queries.get()).isLessThanOrEqualTo(MAX_WORK);
    }
    @Test void noPathAndExternalTargetsAreHonestTerminalResults() {
        var empty = engine.walk("A", Direction.OUTGOING, bounds, graph(), p -> true);
        assertThat(empty.paths()).isEmpty(); assertThat(empty.truncated()).isFalse();
        assertThat(empty.frontiers().getFirst().termination()).isEqualTo("LEAF");
        var external = engine.walk("A", Direction.OUTGOING, bounds, graph(edge("A", null, "RESOLVED")), p -> true);
        assertThat(external.paths().getFirst().termination()).isEqualTo("EXTERNAL");
        assertThat(external.paths().getFirst().resolutionState()).isEqualTo("RESOLVED");
    }
    @Test void frontierOutputIsBoundedAndInvalidBoundsAreRejected() {
        var result = engine.walk("A", Direction.OUTGOING, new Bounds(12, 1, 50),
                graph(edge("A", "B", "RESOLVED"), edge("A", "C", "RESOLVED")), p -> false);
        assertThat(result.frontiers()).hasSize(1);
        assertThat(result.truncationReasons()).contains("FRONTIER_LIMIT");
        assertThatThrownBy(() -> new Bounds(17, 100, 50)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Bounds(1, 501, 50)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Bounds(1, 100, 201)).isInstanceOf(IllegalArgumentException.class);
    }
}
