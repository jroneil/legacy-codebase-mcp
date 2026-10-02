package com.oneil.legacy.scan;

import static com.oneil.legacy.scan.ScanModel.*;

import com.oneil.legacy.database.DatabaseIndexer;
import com.oneil.legacy.framework.FrameworkIndexer;
import com.oneil.legacy.grails.GrailsIndexer;
import com.oneil.legacy.symbol.JavaSymbolIndexer;
import java.io.IOException;
import java.util.ArrayList;
import org.springframework.stereotype.Service;

@Service
public class ScanService {
    private final ScanStore store;
    private final RepositoryInventory inventory;
    private final ScanProperties properties;
    private final MountedRepositoryService repository;
    private final JavaSymbolIndexer indexer;
    private final GrailsIndexer grails;
    private final FrameworkIndexer frameworks;
    private final DatabaseIndexer database;

    public ScanService(ScanStore store, RepositoryInventory inventory, ScanProperties properties,
            MountedRepositoryService repository, JavaSymbolIndexer indexer, GrailsIndexer grails,
            FrameworkIndexer frameworks, DatabaseIndexer database) {
        this.store = store;
        this.inventory = inventory;
        this.properties = properties;
        this.repository = repository;
        this.indexer = indexer;
        this.grails = grails;
        this.frameworks = frameworks;
        this.database = database;
    }

    // Intentionally not transactional: each lifecycle step commits separately.
    public ScanDetail scan() {
        String version = properties.getAnalyzerVersion();
        if (version == null || version.isBlank() || version.length() > 200) {
            throw new ScanConfigurationException();
        }
        var selected = repository.resolve();
        var root = selected.root();
        var id = store.create("mounted:" + selected.metadata().name(), version);
        store.start(id);
        try {
            var collected = inventory.collect(root);
            var index = database.index(root, collected, frameworks.index(root, collected,
                    grails.index(root, collected, indexer.index(root, collected))));
            var errors = new ArrayList<>(collected.errors());
            errors.addAll(index.errors());
            store.complete(id, new Inventory(collected.files(), errors, collected.gitCommitSha(), index));
        } catch (IOException | RuntimeException failure) {
            // Never persist raw exception messages: analyzer and filesystem errors may contain secrets.
            store.fail(id);
        }
        return store.detail(id, 100, 0);
    }

    public static class ScanConfigurationException extends RuntimeException {}
}
