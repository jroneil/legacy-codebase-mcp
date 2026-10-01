package com.oneil.legacy.framework;

import com.oneil.legacy.symbol.SymbolStore.SymbolNotFoundException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/entry-points")
public class FrameworkController {
    private final FrameworkQueries queries;
    public FrameworkController(FrameworkQueries queries) { this.queries = queries; }
    @GetMapping
    public FrameworkQueries.Entries entries(@RequestParam(defaultValue = "") String path,
            @RequestParam(defaultValue = "100") int limit, @RequestParam(defaultValue = "0") int offset) {
        if (path.length() > 2000 || limit < 1 || limit > 200 || offset < 0 || offset > 1_000_000) throw new InvalidQuery();
        return queries.entries(path, limit, offset);
    }
    @GetMapping("/trace")
    public FrameworkQueries.Trace trace(@RequestParam String entryId, @RequestParam(defaultValue = "8") int depth,
            @RequestParam(defaultValue = "100") int limit) {
        if (entryId.length() > 4000 || depth < 0 || depth > 8 || limit < 1 || limit > 100) throw new InvalidQuery();
        return queries.trace(entryId, depth, limit);
    }
    @ExceptionHandler(InvalidQuery.class)
    ResponseEntity<Map<String, String>> invalid() { return ResponseEntity.badRequest().body(Map.of("error", "Invalid query bounds.")); }
    @ExceptionHandler(SymbolNotFoundException.class)
    ResponseEntity<Map<String, String>> missing() { return ResponseEntity.status(404).body(Map.of("error", "Entry point not found in an active completed scan.")); }
    private static class InvalidQuery extends RuntimeException {}
}
