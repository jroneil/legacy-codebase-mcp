package com.oneil.legacy.scan;

import static com.oneil.legacy.scan.ScanModel.*;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.Set;

/** Reads ordinary Git metadata without running Git, hooks, configuration, or target code. */
final class GitRevisionReader {
    String read(SecureDirectoryStream<Path> root, List<AnalysisError> errors) {
        try {
            var attributes = RepositoryInventory.attributes(root, Path.of(".git"));
            if (!attributes.isDirectory() || attributes.isSymbolicLink()) throw new IOException();
            try (var git = root.newDirectoryStream(Path.of(".git"), LinkOption.NOFOLLOW_LINKS)) {
                String head = readText(git, "HEAD", 4096).trim();
                if (sha(head)) return head;
                if (!head.startsWith("ref: refs/")) throw new IOException();
                String ref = head.substring(5);
                if (!ref.matches("refs/[a-zA-Z0-9_./-]+") || List.of(ref.split("/")).contains("..")) throw new IOException();
                try {
                    String value = readText(git, ref, 4096).trim();
                    if (sha(value)) return value;
                    throw new IOException();
                } catch (NoSuchFileException missingLooseRef) {
                    try {
                        for (String line : readText(git, "packed-refs", 4 * 1024 * 1024).split("\n")) {
                            String[] parts = line.trim().split(" ");
                            if (parts.length == 2 && parts[1].equals(ref) && sha(parts[0])) return parts[0];
                        }
                    } catch (NoSuchFileException missingPackedRefs) {
                        // An unborn branch legitimately has no commit.
                    }
                }
                return null;
            }
        } catch (NoSuchFileException noGitDirectory) {
            return null;
        } catch (IOException | SecurityException unsupported) {
            errors.add(AnalysisError.of(".git", "GIT", "GIT_METADATA_UNAVAILABLE",
                    "Git metadata could not be read safely; commit SHA is unavailable."));
            return null;
        }
    }

    private boolean sha(String value) { return value.matches("([0-9a-f]{40}|[0-9a-f]{64})"); }

    private String readText(SecureDirectoryStream<Path> directory, String path, int limit) throws IOException {
        int slash = path.indexOf('/');
        if (slash >= 0) {
            try (var child = directory.newDirectoryStream(Path.of(path.substring(0, slash)), LinkOption.NOFOLLOW_LINKS)) {
                return readText(child, path.substring(slash + 1), limit);
            }
        }
        Path name = Path.of(path);
        if (!RepositoryInventory.attributes(directory, name).isRegularFile()) throw new IOException();
        try (var channel = directory.newByteChannel(name, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            byte[] bytes = Channels.newInputStream(channel).readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException();
            return new String(bytes, StandardCharsets.US_ASCII);
        }
    }
}
