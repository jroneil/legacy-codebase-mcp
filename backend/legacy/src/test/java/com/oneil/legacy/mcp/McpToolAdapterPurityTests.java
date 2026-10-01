package com.oneil.legacy.mcp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.oneil.legacy.framework.FrameworkQueries;
import com.oneil.legacy.scan.ScanModel.Page;
import com.oneil.legacy.symbol.JavaIndexModel.Symbol;
import com.oneil.legacy.symbol.SymbolStore;
import com.oneil.legacy.traversal.TraversalQueries;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * Structural guard: MCP handlers are adapters. They must depend only on the query services REST uses and
 * must not open transactions, hold a JdbcTemplate, or perform any lookup of their own.
 */
class McpToolAdapterPurityTests {
    @Test
    void handlersDependOnlyOnExistingQueryServices() {
        List<Class<?>> dependencies = Arrays.stream(McpTools.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic() && !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .map(Field::getType)
                .toList();
        assertThat(dependencies).containsExactlyInAnyOrder(SymbolStore.class, TraversalQueries.class, FrameworkQueries.class);
        assertThat(McpTools.class.getAnnotations()).noneMatch(annotation -> annotation.annotationType().equals(Transactional.class));
        assertThat(Arrays.stream(McpTools.class.getDeclaredMethods())
                .flatMap(method -> Arrays.stream(method.getAnnotations())))
                .noneMatch(annotation -> annotation.annotationType().equals(Transactional.class));
    }

    @Test
    void searchSymbolsDelegatesToTheSameStoreUsedByRest() {
        var store = mock(SymbolStore.class);
        var symbol = new Symbol("java:type:demo.Customer", "CLASS", "Customer", "demo.Customer", null, "RESOLVED",
                "src/Customer.java", 1, 1, 9, 1);
        when(store.search("Customer", 5, 10)).thenReturn(new SymbolStore.Search(
                new SymbolStore.Freshness(java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"), "sha", "v", java.time.Instant.parse("2026-10-01T00:00:00Z")),
                new Page<>(List.of(symbol), 1, 10, 5, false)));
        var tools = new McpTools(store, mock(TraversalQueries.class), mock(FrameworkQueries.class));

        var response = tools.searchSymbols("Customer", 5, "10");

        verify(store).search("Customer", 5, 10);
        assertThat(response.error()).isNull();
        assertThat(response.symbols()).singleElement().satisfies(view -> assertThat(view.stableId()).isEqualTo("java:type:demo.Customer"));
        assertThat(response.freshness().scanId()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(response.freshness().scanCompletedAt()).isEqualTo("2026-10-01T00:00:00Z");
        verifyNoMoreInteractions(store);
    }

    @Test
    void traversalToolsDelegateBoundsToTheSharedEngineService() {
        var traversal = mock(TraversalQueries.class);
        when(traversal.query(eq("Action"), eq(TraversalQueries.Mode.TRACE),
                eq(com.oneil.legacy.traversal.TraversalEngine.Direction.OUTGOING),
                eq(new com.oneil.legacy.traversal.TraversalEngine.Bounds(3, 7, 2))))
                .thenReturn(new TraversalQueries.Answer(null, "AMBIGUOUS", List.of(), false,
                        com.oneil.legacy.traversal.TraversalEngine.Direction.OUTGOING, null, List.of(), List.of()));
        var tools = new McpTools(mock(SymbolStore.class), traversal, mock(FrameworkQueries.class));

        var response = tools.traceComponent("Action", com.oneil.legacy.traversal.TraversalEngine.Direction.OUTGOING, 3, 7, 2);

        verify(traversal).query("Action", TraversalQueries.Mode.TRACE,
                com.oneil.legacy.traversal.TraversalEngine.Direction.OUTGOING,
                new com.oneil.legacy.traversal.TraversalEngine.Bounds(3, 7, 2));
        assertThat(response.selection()).isEqualTo("AMBIGUOUS");
    }
}
