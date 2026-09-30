package com.oneil.legacy.scan;

import static com.oneil.legacy.scan.ScanModel.*;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/scans")
public class ScanController {
    private final ScanService service;
    private final ScanStore store;
    public ScanController(ScanService service, ScanStore store) { this.service = service; this.store = store; }

    @PostMapping
    public ResponseEntity<ScanDetail> create() {
        ScanDetail result = service.scan();
        return ResponseEntity.created(URI.create("/api/scans/" + result.scan().id())).body(result);
    }

    @GetMapping
    public ScanList list(@RequestParam(defaultValue = "100") int limit, @RequestParam(defaultValue = "0") int offset) {
        bounds(limit, offset);
        return store.list(limit, offset);
    }

    @GetMapping("/{id}")
    public ScanDetail detail(@PathVariable UUID id, @RequestParam(defaultValue = "100") int limit,
                             @RequestParam(defaultValue = "0") int offset) {
        bounds(limit, offset);
        return store.detail(id, limit, offset);
    }

    private void bounds(int limit, int offset) {
        if (limit < 1 || limit > 200 || offset < 0 || offset > 1_000_000) throw new InvalidPageException();
    }
    @ExceptionHandler(ScanStore.ScanNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound() {
        return ResponseEntity.status(404).body(Map.of("error", "Scan not found."));
    }
    @ExceptionHandler(ScanService.ScanConfigurationException.class)
    ResponseEntity<Map<String, String>> configuration() {
        return ResponseEntity.status(503).body(Map.of("error", "Configure LEGACY_REPOSITORY_ROOT and a nonempty ANALYZER_VERSION (maximum 200 characters)."));
    }
    @ExceptionHandler(InvalidPageException.class)
    ResponseEntity<Map<String, String>> invalidPage() {
        return ResponseEntity.badRequest().body(Map.of("error", "limit must be 1..200 and offset 0..1000000."));
    }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, String>> databaseFailure() {
        return ResponseEntity.status(503).body(Map.of("error", "Scan persistence is unavailable."));
    }
    private static class InvalidPageException extends RuntimeException {}
}
