package com.oneil.legacy.scan;

import java.util.ArrayList;
import com.oneil.legacy.symbol.JavaSymbolIndexer;
import com.oneil.legacy.grails.GrailsIndexer;
import com.oneil.legacy.framework.FrameworkIndexer;
import com.oneil.legacy.database.DatabaseIndexer;
import com.oneil.legacy.scan.RepositoryCatalog.RepositorySelection;
import static com.oneil.legacy.scan.ScanModel.*;

import java.io.IOException;
import org.springframework.stereotype.Service;

@Service
public class ScanService {
    private final ScanStore store;
    private final RepositoryInventory inventory;
    private final ScanProperties properties;
    private final RepositoryCatalog repositories;
    private final JavaSymbolIndexer indexer;
    private final GrailsIndexer grails;
    private final FrameworkIndexer frameworks;
    private final DatabaseIndexer database;

    public ScanService(ScanStore store, RepositoryInventory inventory, ScanProperties properties,
            RepositoryCatalog repositories, JavaSymbolIndexer indexer, GrailsIndexer grails,
            FrameworkIndexer frameworks, DatabaseIndexer database) {
        this.store = store;
        this.inventory = inventory;
        this.properties = properties;
        this.repositories = repositories;
        this.indexer = indexer;
        this.grails = grails;
        this.frameworks = frameworks;
        this.database = database;
    }

    // Intentionally not transactional: each lifecycle step commits separately.
    public ScanDetail scan(String repository) {
        String version = properties.getAnalyzerVersion();
        if (version == null || version.isBlank() || version.length() > 200) {
            throw new ScanConfigurationException();
        }
        RepositorySelection selected = repositories.resolve(repository);
        var root = selected.root();
        var id = store.create(root.toString(), version);
        store.start(id);
        try {
            var collected = inventory.collect(root);
            var index = database.index(root, collected, frameworks.index(root, collected,
                    grails.index(root, collected, indexer.index(root, collected))));
            var errors = new ArrayList<>(collected.errors());
            errors.addAll(index.errors());
            store.complete(id, new Inventory(collected.files(), errors, collected.gitCommitSha(), index));
        } catch (IOException | RuntimeException failure) {
            // Never persist raw exception messages: JDBC and filesystem exceptions may contain secrets.
            store.fail(id);
        }
        return store.detail(id, 100, 0);
    }

    public static class ScanConfigurationException extends RuntimeException {}
}
