package com.oneil.legacy.scan;

import static com.oneil.legacy.scan.ScanModel.*;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/repository")
public class MountedRepositoryController {
    private final MountedRepositoryService repository;

    public MountedRepositoryController(MountedRepositoryService repository) {
        this.repository = repository;
    }

    @GetMapping
    public MountedRepository metadata() {
        return repository.metadata();
    }

    @ExceptionHandler(MountedRepositoryService.RepositoryConfigurationException.class)
    ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(503).body(Map.of(
                "error", "No readable repository is mounted. Run the local launcher and retry."));
    }
}
