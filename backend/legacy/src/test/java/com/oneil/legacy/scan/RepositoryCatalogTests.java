package com.oneil.legacy.scan;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryCatalogTests {
    @TempDir Path root;

    @Test
    void browsesRootAndNestedDirectoriesInStableOrder() throws Exception {
        Files.createDirectories(root.resolve("legacy/zeta"));
        Files.createDirectories(root.resolve("legacy/alpha"));
        Files.createDirectories(root.resolve("other/sample-app"));
        Files.createDirectories(root.resolve(".hidden"));
        Files.writeString(root.resolve("ordinary-file.txt"), "not a repository");
        Files.createSymbolicLink(root.resolve("escape-link"), Files.createTempDirectory("slice101-outside-"));
        var catalog = catalog(root);

        var rootResult = catalog.list("");
        assertThat(rootResult.path()).isEmpty();
        assertThat(rootResult.parent()).isNull();
        assertThat(rootResult.items()).extracting(item -> item.id()).containsExactly("legacy", "other");
        assertThat(rootResult.items()).extracting(item -> item.name()).containsExactly("legacy", "other");

        var nested = catalog.list("legacy");
        assertThat(nested.path()).isEqualTo("legacy");
        assertThat(nested.parent()).isEmpty();
        assertThat(nested.items()).extracting(item -> item.id())
                .containsExactly("legacy/alpha", "legacy/zeta");
        assertThat(nested.items()).extracting(item -> item.name()).containsExactly("alpha", "zeta");

        var leaf = catalog.list("other/sample-app");
        assertThat(leaf.path()).isEqualTo("other/sample-app");
        assertThat(leaf.parent()).isEqualTo("other");
        assertThat(leaf.items()).isEmpty();
        assertThat(leaf.totalCount()).isZero();
    }

    @Test
    void boundsLargeRepositoryListsAndKeepsLexicographicallyFirstEntries() throws Exception {
        Path collection = Files.createDirectory(root.resolve("collection"));
        for (int index = 204; index >= 0; index--) {
            Files.createDirectory(collection.resolve("repository-%03d".formatted(index)));
        }

        var result = catalog(root).list("collection");

        assertThat(result.items()).hasSize(RepositoryCatalog.MAX_RESULTS);
        assertThat(result.items().getFirst().id()).isEqualTo("collection/repository-000");
        assertThat(result.items().getLast().id()).isEqualTo("collection/repository-199");
        assertThat(result.totalCount()).isEqualTo(205);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void resolvesNestedRepositoryToItsPhysicalRoot() throws Exception {
        Path repository = Files.createDirectories(root.resolve("legacy/old-struts-app"));

        var selected = catalog(root).resolve("legacy/old-struts-app");

        assertThat(selected.id()).isEqualTo("legacy/old-struts-app");
        assertThat(selected.root()).isEqualTo(repository);
    }

    @Test
    void rejectsAbsoluteTraversalNonNormalizedHiddenMissingAndFiles() throws Exception {
        Files.createDirectories(root.resolve("legacy/repository"));
        Files.createDirectories(root.resolve("legacy/.hidden/repository"));
        Files.writeString(root.resolve("legacy/file"), "not a directory");
        var catalog = catalog(root);

        assertThatThrownBy(() -> catalog.resolve(null)).isInstanceOf(RepositoryCatalog.RepositorySelectionException.class);
        assertThatThrownBy(() -> catalog.resolve(" ")).isInstanceOf(RepositoryCatalog.RepositorySelectionException.class);
        assertThatThrownBy(() -> catalog.resolve("../legacy")).isInstanceOf(RepositoryCatalog.RepositorySelectionException.class);
        assertThatThrownBy(() -> catalog.resolve("legacy/../legacy/repository")).isInstanceOf(RepositoryCatalog.RepositorySelectionException.class);
        assertThatThrownBy(() -> catalog.resolve("legacy//repository")).isInstanceOf(RepositoryCatalog.RepositorySelectionException.class);
        assertThatThrownBy(() -> catalog.resolve("legacy/repository/")).isInstanceOf(RepositoryCatalog.RepositorySelectionException.class);
        assertThatThrownBy(() -> catalog.resolve(root.resolve("legacy/repository").toString())).isInstanceOf(RepositoryCatalog.RepositorySelectionException.class);
        assertThatThrownBy(() -> catalog.resolve("legacy/.hidden/repository")).isInstanceOf(RepositoryCatalog.RepositorySelectionException.class);
        assertThatThrownBy(() -> catalog.resolve("legacy/missing")).isInstanceOf(RepositoryCatalog.RepositorySelectionException.class);
        assertThatThrownBy(() -> catalog.resolve("legacy/file")).isInstanceOf(RepositoryCatalog.RepositorySelectionException.class);
        assertThatThrownBy(() -> catalog.list("legacy/missing")).isInstanceOf(RepositoryCatalog.RepositorySelectionException.class);
        assertThatThrownBy(() -> catalog.list("legacy/file")).isInstanceOf(RepositoryCatalog.RepositorySelectionException.class);
        assertThatThrownBy(() -> catalog.list(".hidden")).isInstanceOf(RepositoryCatalog.RepositorySelectionException.class);
    }

    @Test
    void rejectsIntermediateAndFinalSymlinksIncludingPhysicalEscape() throws Exception {
        Files.createDirectories(root.resolve("legacy/repository"));
        Path outside = Files.createTempDirectory("slice101-outside-");
        Files.createDirectory(outside.resolve("repository"));
        Files.createSymbolicLink(root.resolve("intermediate-link"), root.resolve("legacy"));
        Files.createSymbolicLink(root.resolve("legacy/final-link"), root.resolve("legacy/repository"));
        Files.createSymbolicLink(root.resolve("escape-link"), outside);
        var catalog = catalog(root);

        assertThatThrownBy(() -> catalog.resolve("intermediate-link/repository"))
                .isInstanceOf(RepositoryCatalog.RepositorySelectionException.class);
        assertThatThrownBy(() -> catalog.resolve("legacy/final-link"))
                .isInstanceOf(RepositoryCatalog.RepositorySelectionException.class);
        assertThatThrownBy(() -> catalog.resolve("escape-link/repository"))
                .isInstanceOf(RepositoryCatalog.RepositorySelectionException.class);
        assertThatThrownBy(() -> catalog.list("intermediate-link"))
                .isInstanceOf(RepositoryCatalog.RepositorySelectionException.class);
        assertThatThrownBy(() -> catalog.list("escape-link"))
                .isInstanceOf(RepositoryCatalog.RepositorySelectionException.class);
    }

    @Test
    void rejectsPathsBeyondDepthAndLengthBounds() throws Exception {
        String tooDeep = String.join("/", java.util.Collections.nCopies(RepositoryCatalog.MAX_DEPTH + 1, "a"));
        String tooLong = "a".repeat(RepositoryCatalog.MAX_IDENTIFIER_LENGTH + 1);
        var catalog = catalog(root);

        assertThatThrownBy(() -> catalog.list(tooDeep)).isInstanceOf(RepositoryCatalog.RepositorySelectionException.class);
        assertThatThrownBy(() -> catalog.resolve(tooLong)).isInstanceOf(RepositoryCatalog.RepositorySelectionException.class);
    }

    @Test
    void rejectsMissingRelativeNonDirectoryAndSymlinkedBases() throws Exception {
        Path repository = Files.createDirectories(root.resolve("legacy/repository"));
        Path file = Files.writeString(root.resolve("file"), "not a directory");
        Path linkedBase = root.resolve("linked-base");
        Files.createSymbolicLink(linkedBase, root);

        assertThatThrownBy(() -> catalog((String) null).list()).isInstanceOf(RepositoryCatalog.RepositoryConfigurationException.class);
        assertThatThrownBy(() -> catalog("").list()).isInstanceOf(RepositoryCatalog.RepositoryConfigurationException.class);
        assertThatThrownBy(() -> catalog(Path.of("relative")).list()).isInstanceOf(RepositoryCatalog.RepositoryConfigurationException.class);
        assertThatThrownBy(() -> catalog(root.resolve("missing")).list()).isInstanceOf(RepositoryCatalog.RepositoryConfigurationException.class);
        assertThatThrownBy(() -> catalog(file).list()).isInstanceOf(RepositoryCatalog.RepositoryConfigurationException.class);
        assertThatThrownBy(() -> catalog(linkedBase).list()).isInstanceOf(RepositoryCatalog.RepositoryConfigurationException.class);
        assertThatCode(() -> catalog(root).resolve(root.relativize(repository).toString())).doesNotThrowAnyException();
    }

    private RepositoryCatalog catalog(Path base) {
        return catalog(base == null ? null : base.toString());
    }

    private RepositoryCatalog catalog(String base) {
        var properties = new ScanProperties();
        properties.setRepositoryBase(base);
        return new RepositoryCatalog(properties);
    }
}
