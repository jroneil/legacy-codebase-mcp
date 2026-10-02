package com.oneil.legacy.scan;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryCatalogTests {
    @TempDir Path root;

    @Test
    void listsOnlyImmediateVisibleDirectoriesInStableBoundedOrder() throws Exception {
        Files.createDirectories(root.resolve("zeta/nested"));
        Files.createDirectories(root.resolve("alpha"));
        Files.createDirectories(root.resolve(".hidden"));
        Files.writeString(root.resolve("ordinary-file"), "not a repository");
        Files.createSymbolicLink(root.resolve("linked"), root.resolve("alpha"));
        var catalog = catalog(root);

        var result = catalog.list();

        assertThat(result.items()).extracting(item -> item.id()).containsExactly("alpha", "zeta");
        assertThat(result.items()).extracting(item -> item.name()).containsExactly("alpha", "zeta");
        assertThat(result.totalCount()).isEqualTo(2);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void boundsLargeRepositoryListsAndKeepsLexicographicallyFirstEntries() throws Exception {
        for (int index = 204; index >= 0; index--) {
            Files.createDirectory(root.resolve("repository-%03d".formatted(index)));
        }

        var result = catalog(root).list();

        assertThat(result.items()).hasSize(RepositoryCatalog.MAX_RESULTS);
        assertThat(result.items().getFirst().id()).isEqualTo("repository-000");
        assertThat(result.items().getLast().id()).isEqualTo("repository-199");
        assertThat(result.totalCount()).isEqualTo(205);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void resolvesAnImmediateChildToItsPhysicalRoot() throws Exception {
        Path repository = Files.createDirectory(root.resolve("legacy-struts"));

        var selected = catalog(root).resolve("legacy-struts");

        assertThat(selected.id()).isEqualTo("legacy-struts");
        assertThat(selected.root()).isEqualTo(repository);
    }

    @Test
    void rejectsTraversalAbsoluteNestedHiddenMissingFilesAndSymlinks() throws Exception {
        Files.createDirectories(root.resolve("repository/nested"));
        Files.createDirectory(root.resolve(".hidden"));
        Files.writeString(root.resolve("file"), "not a directory");
        Files.createSymbolicLink(root.resolve("linked-repository"), root.resolve("repository"));
        Path outside = Files.createTempDirectory("slice10-outside-");
        Files.createSymbolicLink(root.resolve("escape"), outside);
        var catalog = catalog(root);

        assertThatThrownBy(() -> catalog.resolve(null)).isInstanceOf(RepositoryCatalog.RepositorySelectionException.class);
        assertThatThrownBy(() -> catalog.resolve(" ")).isInstanceOf(RepositoryCatalog.RepositorySelectionException.class);
        assertThatThrownBy(() -> catalog.resolve("../repository")).isInstanceOf(RepositoryCatalog.RepositorySelectionException.class);
        assertThatThrownBy(() -> catalog.resolve("repository/../repository")).isInstanceOf(RepositoryCatalog.RepositorySelectionException.class);
        assertThatThrownBy(() -> catalog.resolve("repository/nested")).isInstanceOf(RepositoryCatalog.RepositorySelectionException.class);
        assertThatThrownBy(() -> catalog.resolve(root.resolve("repository").toString())).isInstanceOf(RepositoryCatalog.RepositorySelectionException.class);
        assertThatThrownBy(() -> catalog.resolve(".hidden")).isInstanceOf(RepositoryCatalog.RepositorySelectionException.class);
        assertThatThrownBy(() -> catalog.resolve("missing")).isInstanceOf(RepositoryCatalog.RepositorySelectionException.class);
        assertThatThrownBy(() -> catalog.resolve("file")).isInstanceOf(RepositoryCatalog.RepositorySelectionException.class);
        assertThatThrownBy(() -> catalog.resolve("linked-repository")).isInstanceOf(RepositoryCatalog.RepositorySelectionException.class);
        assertThatThrownBy(() -> catalog.resolve("escape")).isInstanceOf(RepositoryCatalog.RepositorySelectionException.class);
    }

    @Test
    void rejectsMissingRelativeNonDirectoryAndSymlinkedBases() throws Exception {
        Path repository = Files.createDirectory(root.resolve("repository"));
        Path file = Files.writeString(root.resolve("file"), "not a directory");
        Path linkedBase = root.resolve("linked-base");
        Files.createSymbolicLink(linkedBase, root);

        assertThatThrownBy(() -> catalog((String) null).list()).isInstanceOf(RepositoryCatalog.RepositoryConfigurationException.class);
        assertThatThrownBy(() -> catalog("").list()).isInstanceOf(RepositoryCatalog.RepositoryConfigurationException.class);
        assertThatThrownBy(() -> catalog(Path.of("relative")).list()).isInstanceOf(RepositoryCatalog.RepositoryConfigurationException.class);
        assertThatThrownBy(() -> catalog(root.resolve("missing")).list()).isInstanceOf(RepositoryCatalog.RepositoryConfigurationException.class);
        assertThatThrownBy(() -> catalog(file).list()).isInstanceOf(RepositoryCatalog.RepositoryConfigurationException.class);
        assertThatThrownBy(() -> catalog(linkedBase).list()).isInstanceOf(RepositoryCatalog.RepositoryConfigurationException.class);
        assertThatCode(() -> catalog(root).resolve(repository.getFileName().toString())).doesNotThrowAnyException();
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
