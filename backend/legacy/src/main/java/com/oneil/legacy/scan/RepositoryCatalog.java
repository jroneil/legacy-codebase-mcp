package com.oneil.legacy.scan;

import static com.oneil.legacy.scan.ScanModel.*;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.util.*;
import org.springframework.stereotype.Component;

/** Lists and resolves repositories only inside the configured read-only namespace. */
@Component
public class RepositoryCatalog {
    static final int MAX_RESULTS = 200;
    static final int MAX_IDENTIFIER_LENGTH = 1000;
    private final ScanProperties properties;

    public RepositoryCatalog(ScanProperties properties) { this.properties = properties; }

    public RepositoryList list() {
        Path base = configuredBase();
        var retained = new PriorityQueue<RepositoryItem>(MAX_RESULTS,
                Comparator.comparing(RepositoryItem::id).reversed());
        long count = 0;
        try (var directory = RepositoryInventory.openRoot(base)) {
            for (Path entry : directory) {
                Path name = entry.getFileName();
                String id = name.toString();
                if (!validComponent(id)) continue;
                var view = directory.getFileAttributeView(name, BasicFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS);
                var attributes = view.readAttributes();
                if (!attributes.isDirectory() || attributes.isSymbolicLink()) continue;
                count++;
                var item = new RepositoryItem(id, id);
                if (retained.size() < MAX_RESULTS) retained.add(item);
                else if (item.id().compareTo(retained.peek().id()) < 0) {
                    retained.poll();
                    retained.add(item);
                }
            }
        } catch (IOException | RuntimeException failure) {
            throw new RepositoryConfigurationException();
        }
        var items = new ArrayList<>(retained);
        items.sort(Comparator.comparing(RepositoryItem::id));
        return new RepositoryList(List.copyOf(items), count, count > items.size());
    }

    public RepositorySelection resolve(String repository) {
        Path relative = relative(repository);
        Path base = configuredBase();
        Path selected = base.resolve(relative).normalize();
        if (!selected.startsWith(base)) throw new RepositorySelectionException();
        try (var ignored = RepositoryInventory.openRoot(selected)) {
            Path physicalBase = base.toRealPath();
            Path physicalSelected = selected.toRealPath();
            if (!physicalSelected.startsWith(physicalBase) || physicalSelected.equals(physicalBase)) {
                throw new RepositorySelectionException();
            }
        } catch (RepositorySelectionException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new RepositorySelectionException();
        }
        return new RepositorySelection(canonical(relative), selected);
    }

    private Path configuredBase() {
        String configured = properties.getRepositoryBase();
        if (configured == null || configured.isBlank()) throw new RepositoryConfigurationException();
        try {
            Path base = Path.of(configured);
            if (!base.isAbsolute()) throw new RepositoryConfigurationException();
            base = base.normalize();
            try (var ignored = RepositoryInventory.openRoot(base)) {
                if (!base.toRealPath().equals(base)) throw new RepositoryConfigurationException();
            }
            return base;
        } catch (RepositoryConfigurationException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new RepositoryConfigurationException();
        }
    }

    private Path relative(String repository) {
        if (repository == null || repository.isBlank() || !repository.equals(repository.trim())
                || repository.length() > MAX_IDENTIFIER_LENGTH || repository.indexOf('\\') >= 0) {
            throw new RepositorySelectionException();
        }
        try {
            Path relative = Path.of(repository);
            if (relative.isAbsolute() || relative.getNameCount() != 1
                    || !relative.normalize().equals(relative)) throw new RepositorySelectionException();
            for (Path component : relative) if (!validComponent(component.toString())) throw new RepositorySelectionException();
            return relative;
        } catch (InvalidPathException failure) {
            throw new RepositorySelectionException();
        }
    }

    private boolean validComponent(String value) {
        return !value.isBlank() && !value.equals(".") && !value.equals("..") && !value.startsWith(".");
    }

    private String canonical(Path relative) {
        var names = new ArrayList<String>();
        for (Path component : relative) names.add(component.toString());
        return String.join("/", names);
    }

    public record RepositorySelection(String id, Path root) {}
    public static class RepositoryConfigurationException extends RuntimeException {}
    public static class RepositorySelectionException extends RuntimeException {}
}
