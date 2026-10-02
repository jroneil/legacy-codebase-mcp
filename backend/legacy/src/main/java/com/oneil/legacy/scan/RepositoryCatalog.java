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
    static final int MAX_DEPTH = 32;
    private final ScanProperties properties;

    public RepositoryCatalog(ScanProperties properties) { this.properties = properties; }

    public RepositoryList list() { return list(""); }

    public RepositoryList list(String requestedPath) {
        Path base = configuredBase();
        Path relative = browserPath(requestedPath);
        Path selected = relative == null ? base : base.resolve(relative).normalize();
        String path = relative == null ? "" : canonical(relative);
        verifySelected(base, selected, false);

        var retained = new PriorityQueue<RepositoryItem>(MAX_RESULTS,
                Comparator.comparing(RepositoryItem::id).reversed());
        long count = 0;
        try (var directory = RepositoryInventory.openRoot(selected)) {
            for (Path entry : directory) {
                Path name = entry.getFileName();
                String childName = name.toString();
                if (!validComponent(childName)) continue;
                var view = directory.getFileAttributeView(name, BasicFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS);
                var attributes = view.readAttributes();
                if (!attributes.isDirectory() || attributes.isSymbolicLink()) continue;
                count++;
                String id = path.isEmpty() ? childName : path + "/" + childName;
                var item = new RepositoryItem(id, childName);
                if (retained.size() < MAX_RESULTS) retained.add(item);
                else if (item.id().compareTo(retained.peek().id()) < 0) {
                    retained.poll();
                    retained.add(item);
                }
            }
        } catch (IOException | RuntimeException failure) {
            throw new RepositorySelectionException();
        }
        var items = new ArrayList<>(retained);
        items.sort(Comparator.comparing(RepositoryItem::id));
        return new RepositoryList(path, parent(relative), List.copyOf(items), count, count > items.size());
    }

    public RepositorySelection resolve(String repository) {
        Path relative = repositoryPath(repository);
        Path base = configuredBase();
        Path selected = base.resolve(relative).normalize();
        verifySelected(base, selected, true);
        return new RepositorySelection(canonical(relative), selected);
    }

    private void verifySelected(Path base, Path selected, boolean requireChild) {
        if (!selected.startsWith(base) || (requireChild && selected.equals(base))) {
            throw new RepositorySelectionException();
        }
        try (var ignored = RepositoryInventory.openRoot(selected)) {
            Path physicalBase = base.toRealPath();
            Path physicalSelected = selected.toRealPath();
            if (!physicalSelected.startsWith(physicalBase)
                    || (requireChild && physicalSelected.equals(physicalBase))) {
                throw new RepositorySelectionException();
            }
        } catch (RepositorySelectionException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new RepositorySelectionException();
        }
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

    private Path browserPath(String value) {
        if (value == null || value.isEmpty()) return null;
        return validatedRelative(value);
    }

    private Path repositoryPath(String value) {
        if (value == null || value.isBlank()) throw new RepositorySelectionException();
        return validatedRelative(value);
    }

    private Path validatedRelative(String value) {
        if (!value.equals(value.trim()) || value.length() > MAX_IDENTIFIER_LENGTH
                || value.indexOf('\\') >= 0) {
            throw new RepositorySelectionException();
        }
        try {
            Path relative = Path.of(value);
            if (relative.isAbsolute() || relative.getNameCount() > MAX_DEPTH
                    || !relative.normalize().equals(relative)) {
                throw new RepositorySelectionException();
            }
            for (Path component : relative) {
                if (!validComponent(component.toString())) throw new RepositorySelectionException();
            }
            if (!canonical(relative).equals(value)) throw new RepositorySelectionException();
            return relative;
        } catch (InvalidPathException failure) {
            throw new RepositorySelectionException();
        }
    }

    private boolean validComponent(String value) {
        return !value.isBlank() && !value.equals(".") && !value.equals("..") && !value.startsWith(".");
    }

    private String parent(Path relative) {
        if (relative == null) return null;
        Path parent = relative.getParent();
        return parent == null ? "" : canonical(parent);
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
