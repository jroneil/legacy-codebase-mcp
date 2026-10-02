package com.oneil.legacy.scan;

import static com.oneil.legacy.scan.ScanModel.*;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/repositories")
public class RepositoryController {
    private final RepositoryCatalog repositories;
    public RepositoryController(RepositoryCatalog repositories) { this.repositories = repositories; }

    @GetMapping
    public RepositoryList list() { return repositories.list(); }

    @ExceptionHandler(RepositoryCatalog.RepositoryConfigurationException.class)
    ResponseEntity<Map<String, String>> configuration() {
        return ResponseEntity.status(503).body(Map.of("error", "Configure a readable, secure LEGACY_REPOSITORY_BASE directory."));
    }
}
