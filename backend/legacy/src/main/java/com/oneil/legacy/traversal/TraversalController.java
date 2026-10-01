package com.oneil.legacy.traversal;

import com.oneil.legacy.symbol.SymbolStore.SymbolNotFoundException;
import static com.oneil.legacy.traversal.TraversalEngine.*;
import static com.oneil.legacy.traversal.TraversalQueries.*;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/relationships")
public class TraversalController {
    private final TraversalQueries queries;
    public TraversalController(TraversalQueries queries) { this.queries = queries; }
    @GetMapping("/trace")
    public Answer trace(@RequestParam String component, @RequestParam(defaultValue = "OUTGOING") Direction direction,
                        @RequestParam(defaultValue = "12") int depth, @RequestParam(defaultValue = "100") int limit,
                        @RequestParam(defaultValue = "50") int fanOut) {
        return queries.query(component, Mode.TRACE, direction, new Bounds(depth, limit, fanOut));
    }
    @GetMapping("/database-tables")
    public Answer tables(@RequestParam String component, @RequestParam(defaultValue = "12") int depth,
                         @RequestParam(defaultValue = "100") int limit, @RequestParam(defaultValue = "50") int fanOut) {
        return queries.query(component, Mode.DATABASE_TABLES, Direction.OUTGOING, new Bounds(depth, limit, fanOut));
    }
    @GetMapping("/table-usages")
    public Answer usages(@RequestParam String table, @RequestParam(defaultValue = "12") int depth,
                         @RequestParam(defaultValue = "100") int limit, @RequestParam(defaultValue = "50") int fanOut) {
        return queries.query(table, Mode.TABLE_USAGES, Direction.INCOMING, new Bounds(depth, limit, fanOut));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid() { return ResponseEntity.badRequest().body(Map.of("error", "Invalid traversal query or bounds.")); }
    @ExceptionHandler(SymbolNotFoundException.class)
    ResponseEntity<Map<String, String>> missing() { return ResponseEntity.status(404).body(Map.of("error", "Symbol not found in an active completed scan.")); }
}
