package com.oneil.legacy.scan;

import static com.oneil.legacy.scan.ScanModel.*;

import java.io.IOException;
import java.nio.file.Path;
import org.springframework.stereotype.Service;

/** Resolves the single repository selected and mounted read-only by the host launcher. */
@Service
public class MountedRepositoryService {
    private final ScanProperties properties;

    public MountedRepositoryService(ScanProperties properties) {
        this.properties = properties;
    }

    public Selection resolve() {
        if (!properties.isRepositoryConfigured()) throw new RepositoryConfigurationException();
        String configured = properties.getRepositoryRoot();
        String name = properties.getRepositoryName();
        if (configured == null || configured.isBlank() || name == null || !validName(name)) {
            throw new RepositoryConfigurationException();
        }
        try {
            Path root = Path.of(configured);
            if (!root.isAbsolute()) throw new RepositoryConfigurationException();
            root = root.normalize();
            try (var ignored = RepositoryInventory.openRoot(root)) {
                if (!root.toRealPath().equals(root)) throw new RepositoryConfigurationException();
            }
            return new Selection(new MountedRepository(name, "READY"), root);
        } catch (RepositoryConfigurationException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new RepositoryConfigurationException();
        }
    }

    public MountedRepository metadata() {
        return resolve().metadata();
    }

    private boolean validName(String value) {
        return value.equals(value.trim()) && !value.isEmpty() && value.length() <= 200
                && value.indexOf('/') < 0 && value.indexOf('\\') < 0
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    public record Selection(MountedRepository metadata, Path root) {}
    public static class RepositoryConfigurationException extends RuntimeException {}
}
