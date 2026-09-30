package com.oneil.legacy.scan;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryInventoryTests {
    @TempDir Path root;
    private final RepositoryInventory inventory = new RepositoryInventory();

    @Test
    void inventoriesEverySupportedTypeWithStableHashesAndRelativePaths() throws Exception {
        for (String type : Set.of("java", "groovy", "xml", "sql", "jsp", "gsp", "properties")) {
            write("nested/deep/example." + type, "sample text\n");
        }
        var result = inventory.collect(root);
        assertThat(result.files()).hasSize(7);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("sample text\n".getBytes(StandardCharsets.UTF_8)));
        assertThat(result.files()).allSatisfy(file -> {
            assertThat(file.relativePath()).startsWith("nested/deep/example.");
            assertThat(file.contentHash()).isEqualTo(hash);
            assertThat(file.encoding()).isEqualTo("UTF-8");
            assertThat(file.sizeBytes()).isEqualTo(12);
            assertThat(file.stableId()).isEqualTo("file:" + file.relativePath());
        });
        assertThat(result.errors()).isEmpty();
        assertThat(result.gitCommitSha()).isNull();
        assertThat(inventory.collect(root)).isEqualTo(result);
    }

    @Test
    void ignoresGeneratedDirectoriesAtEveryDepthAndUnsupportedOrBinaryFiles() throws Exception {
        for (String ignored : Set.of(".git", "target", "build", ".gradle", "node_modules")) {
            write(ignored + "/hidden.java", "class Hidden {}");
            write("nested/" + ignored + "/hidden.java", "class Hidden {}");
        }
        write("visible.java", "class Visible {}");
        write("image.png", "not source");
        write("README.md", "not source");
        Files.write(root.resolve("binary.java"), new byte[] { 0, 1, 2, 3 });
        assertThat(inventory.collect(root).files()).extracting(ScanModel.SourceFile::relativePath).containsExactly("visible.java");
    }

    @Test
    void neverFollowsFileDirectoryRootOrParentSymlinks() throws Exception {
        Path outside = Files.createDirectory(root.resolve("outside"));
        Path repository = Files.createDirectory(root.resolve("repository"));
        Files.writeString(outside.resolve("secret.java"), "private source");
        Files.createSymbolicLink(repository.resolve("escape"), outside);
        Files.createSymbolicLink(repository.resolve("escape.java"), outside.resolve("secret.java"));
        Files.createSymbolicLink(repository.resolve("cycle"), repository);
        Files.createSymbolicLink(repository.resolve("dangling.java"), outside.resolve("absent.java"));
        Files.writeString(repository.resolve("safe.java"), "class Safe {}");
        assertThat(inventory.collect(repository).files()).extracting(ScanModel.SourceFile::relativePath).containsExactly("safe.java");
        Files.createSymbolicLink(root.resolve("alias"), repository);
        assertThatIOException().isThrownBy(() -> inventory.collect(root.resolve("alias")));
        Files.createDirectory(repository.resolve("child"));
        assertThatIOException().isThrownBy(() -> inventory.collect(root.resolve("alias/child")));
    }

    @Test
    void malformedSourceIsInventoryAndInvalidEncodingPreservesByteHash() throws Exception {
        write("broken.java", "class } deliberately invalid");
        write("broken.xml", "<unclosed>");
        byte[] invalid = {(byte) 0xc3, 0x28};
        Files.write(root.resolve("legacy.properties"), invalid);
        var result = inventory.collect(root);
        assertThat(result.files()).hasSize(3);
        var legacy = result.files().stream().filter(f -> f.relativePath().equals("legacy.properties")).findFirst().orElseThrow();
        assertThat(legacy.encoding()).isEqualTo("UNKNOWN");
        assertThat(legacy.contentHash()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(invalid)));
        assertThat(result.errors()).singleElement().satisfies(error -> {
            assertThat(error.relativePath()).isEqualTo("legacy.properties");
            assertThat(error.code()).isEqualTo("INVALID_UTF8");
            assertThat(error.resolutionState()).isEqualTo("UNRESOLVED");
        });
    }

    @Test
    void bomMarkedUtf16IsRecognizedAsTextInsteadOfBinary() throws Exception {
        Files.write(root.resolve("little.xml"), "\ufeff<root/>".getBytes(StandardCharsets.UTF_16LE));
        Files.write(root.resolve("big.properties"), "\ufeffname=value".getBytes(StandardCharsets.UTF_16BE));
        var result = inventory.collect(root);
        assertThat(result.files()).extracting(ScanModel.SourceFile::encoding).containsExactly("UTF-16BE", "UTF-16LE");
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void unreadableFileAndDirectoryAreLocalizedErrors() throws Exception {
        write("good.java", "class Good {}");
        Path unreadable = write("unreadable.java", "password=do-not-return-this");
        Path directory = Files.createDirectory(root.resolve("private"));
        Files.writeString(directory.resolve("hidden.java"), "hidden");
        Files.setPosixFilePermissions(unreadable, Set.of());
        Files.setPosixFilePermissions(directory, Set.of());
        try {
            var result = inventory.collect(root);
            assertThat(result.files()).extracting(ScanModel.SourceFile::relativePath).containsExactly("good.java");
            assertThat(result.errors()).extracting(ScanModel.AnalysisError::relativePath).containsExactly("private", "unreadable.java");
            assertThat(result.errors()).allSatisfy(error -> {
                assertThat(error.code()).isEqualTo("READ_FAILED");
                assertThat(error.message()).doesNotContain("password", "do-not-return", root.toString());
            });
        } finally {
            Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("rw-------"));
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void targetContentsAndModificationTimesRemainUnchanged() throws Exception {
        write("src/A.java", "class A {}");
        write("config/settings.properties", "password=local-fixture-secret");
        Map<String, String> before = treeState();
        inventory.collect(root);
        inventory.collect(root);
        assertThat(treeState()).isEqualTo(before);
    }

    @Test
    void readsLooseDetachedAndPackedGitRevisionsWithoutExecutingRepositoryCode() throws Exception {
        String sha = "0123456789abcdef0123456789abcdef01234567";
        write(".git/HEAD", "ref: refs/heads/main\n");
        Path ref = write(".git/refs/heads/main", sha + "\n");
        assertThat(inventory.collect(root).gitCommitSha()).isEqualTo(sha);
        Files.delete(ref);
        write(".git/packed-refs", "# pack-refs with: peeled\n" + sha + " refs/heads/main\n");
        assertThat(inventory.collect(root).gitCommitSha()).isEqualTo(sha);
        write(".git/HEAD", sha + "\n");
        assertThat(inventory.collect(root).gitCommitSha()).isEqualTo(sha);
    }

    @Test
    void gitMetadataCannotEscapeThroughRefTraversalOrSymlinks() throws Exception {
        write(".git/HEAD", "ref: refs/../../outside\n");
        assertThat(inventory.collect(root).errors()).extracting(ScanModel.AnalysisError::code).containsExactly("GIT_METADATA_UNAVAILABLE");
        write(".git/HEAD", "ref: refs/heads/main\n");
        Path outside = write("outside", "0123456789abcdef0123456789abcdef01234567");
        Files.createDirectories(root.resolve(".git/refs/heads"));
        Files.createSymbolicLink(root.resolve(".git/refs/heads/main"), outside);
        var result = inventory.collect(root);
        assertThat(result.gitCommitSha()).isNull();
        assertThat(result.errors()).extracting(ScanModel.AnalysisError::code).containsExactly("GIT_METADATA_UNAVAILABLE");
    }

    @Test
    void unavailableWorktreeMetadataIsExplicitAndUnbornGitHasNoSha() throws Exception {
        write(".git", "gitdir: /outside/worktree");
        var worktree = inventory.collect(root);
        assertThat(worktree.gitCommitSha()).isNull();
        assertThat(worktree.errors()).extracting(ScanModel.AnalysisError::code).containsExactly("GIT_METADATA_UNAVAILABLE");
        Files.delete(root.resolve(".git"));
        write(".git/HEAD", "ref: refs/heads/main\n");
        assertThat(inventory.collect(root).gitCommitSha()).isNull();
        assertThat(inventory.collect(root).errors()).isEmpty();
    }

    private Path write(String relative, String content) throws IOException {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        return Files.writeString(path, content);
    }
    private Map<String, String> treeState() throws IOException {
        Map<String, String> state = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                state.put(root.relativize(path).toString(), Files.readString(path) + Files.getLastModifiedTime(path));
            }
        }
        return state;
    }
}
