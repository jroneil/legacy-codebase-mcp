package com.oneil.legacy.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MountedRepositoryServiceTests {
    @TempDir Path tempDir;

    @Test
    void exposesOnlySafeMetadataWhileKeepingThePhysicalRootInternal() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("legacy app"));
        ScanProperties properties = configured(root, "legacy app");

        var selection = new MountedRepositoryService(properties).resolve();

        assertThat(selection.root()).isEqualTo(root.toRealPath());
        assertThat(selection.metadata().name()).isEqualTo("legacy app");
        assertThat(selection.metadata().status()).isEqualTo("READY");
        assertThat(selection.metadata().toString()).doesNotContain(root.toString());
    }

    @Test
    void rejectsMissingRelativeOrInvalidConfiguration() {
        ScanProperties properties = new ScanProperties();
        var service = new MountedRepositoryService(properties);
        assertThatThrownBy(service::resolve)
                .isInstanceOf(MountedRepositoryService.RepositoryConfigurationException.class);

        properties.setRepositoryConfigured(true);
        properties.setRepositoryRoot("relative/path");
        properties.setRepositoryName("legacy");
        assertThatThrownBy(service::resolve)
                .isInstanceOf(MountedRepositoryService.RepositoryConfigurationException.class);

        properties.setRepositoryRoot(tempDir.resolve("missing").toString());
        assertThatThrownBy(service::resolve)
                .isInstanceOf(MountedRepositoryService.RepositoryConfigurationException.class);

        properties.setRepositoryRoot(tempDir.toString());
        properties.setRepositoryName("../legacy");
        assertThatThrownBy(service::resolve)
                .isInstanceOf(MountedRepositoryService.RepositoryConfigurationException.class);
    }

    @Test
    void rejectsASymlinkAsTheConfiguredRepositoryRoot() throws Exception {
        Path actual = Files.createDirectory(tempDir.resolve("actual"));
        Path link = tempDir.resolve("link");
        Files.createSymbolicLink(link, actual);

        assertThatThrownBy(() -> new MountedRepositoryService(configured(link, "link")).resolve())
                .isInstanceOf(MountedRepositoryService.RepositoryConfigurationException.class);
    }

    private ScanProperties configured(Path root, String name) {
        ScanProperties properties = new ScanProperties();
        properties.setRepositoryRoot(root.toAbsolutePath().normalize().toString());
        properties.setRepositoryName(name);
        properties.setRepositoryConfigured(true);
        return properties;
    }
}
