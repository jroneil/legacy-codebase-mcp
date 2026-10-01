package com.oneil.legacy.symbol;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/symbols")
public class SymbolController {
    private final SymbolStore store;
    public SymbolController(SymbolStore store) { this.store = store; }

    @GetMapping("/search")
    public SymbolStore.Search search(@RequestParam(defaultValue = "") String q,
                                    @RequestParam(defaultValue = "100") int limit, @RequestParam(defaultValue = "0") int offset) {
        bounds(limit, offset);
        if (q.length() > 1000) throw new InvalidQueryException();
        return store.search(q.trim(), limit, offset);
    }
    @GetMapping("/{stableId}")
    public SymbolStore.Detail detail(@PathVariable String stableId, @RequestParam(defaultValue = "100") int limit,
                                    @RequestParam(defaultValue = "0") int offset) {
        bounds(limit, offset);
        return store.detail(stableId, limit, offset);
    }
    @GetMapping("/{stableId}/usages")
    public SymbolStore.Usages usages(@PathVariable String stableId, @RequestParam(defaultValue = "100") int limit,
                                    @RequestParam(defaultValue = "0") int offset) {
        bounds(limit, offset);
        return store.usages(stableId, limit, offset);
    }
    // Configuration identities contain repository-relative paths; query parameters preserve slashes.
    @GetMapping("/detail")
    public SymbolStore.Detail detailQuery(@RequestParam String stableId, @RequestParam(defaultValue = "100") int limit,
                                         @RequestParam(defaultValue = "0") int offset) {
        return detail(stableId, limit, offset);
    }
    @GetMapping("/usages")
    public SymbolStore.Usages usagesQuery(@RequestParam String stableId, @RequestParam(defaultValue = "100") int limit,
                                         @RequestParam(defaultValue = "0") int offset) {
        return usages(stableId, limit, offset);
    }
    private void bounds(int limit, int offset) {
        if (limit < 1 || limit > 200 || offset < 0 || offset > 1_000_000) throw new InvalidQueryException();
    }
    @ExceptionHandler(SymbolStore.SymbolNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound() {
        return ResponseEntity.status(404).body(Map.of("error", "Symbol not found in an active completed scan."));
    }
    @ExceptionHandler(InvalidQueryException.class)
    ResponseEntity<Map<String, String>> invalid() {
        return ResponseEntity.badRequest().body(Map.of("error", "Use limit 1..200, offset 0..1000000 and query length at most 1000."));
    }
    private static class InvalidQueryException extends RuntimeException {}
}
