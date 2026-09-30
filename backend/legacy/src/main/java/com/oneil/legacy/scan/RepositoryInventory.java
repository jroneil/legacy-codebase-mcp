package com.oneil.legacy.scan;

import static com.oneil.legacy.scan.ScanModel.*;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class RepositoryInventory {
    private static final Set<String> IGNORED = Set.of(".git", "target", "build", ".gradle", "node_modules");
    private static final Set<String> TYPES = Set.of("java", "groovy", "xml", "sql", "jsp", "gsp", "properties");

    public Inventory collect(Path root) throws IOException {
        var files = new ArrayList<SourceFile>();
        var errors = new ArrayList<AnalysisError>();
        // All operations are relative to open directory handles; no path check/open race.
        try (var directory = openRoot(root)) {
            String gitSha = new GitRevisionReader().read(directory, errors);
            walk(directory, "", files, errors);
            files.sort(Comparator.comparing(SourceFile::relativePath));
            errors.sort(Comparator.comparing((AnalysisError e) -> Objects.toString(e.relativePath(), ""))
                    .thenComparing(AnalysisError::code));
            return new Inventory(files, errors, gitSha);
        }
    }

    /** Re-read only inventoried bytes, without following any path component's symlinks. */
    public byte[] readVerified(Path root, SourceFile file) throws IOException {
        if (file.sizeBytes() > 8 * 1024 * 1024) throw new IOException("Java source exceeds parser limit");
        Path relative = Path.of(file.relativePath());
        if (relative.isAbsolute() || !relative.normalize().equals(relative) || relative.startsWith("..")) {
            throw new IOException("Invalid relative path");
        }
        try (var directory = openRoot(root)) {
            byte[] bytes = readRelative(directory, relative, 0);
            try {
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                if (!hash.equals(file.contentHash())) throw new IOException("Source changed since inventory");
            } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
            return bytes;
        }
    }

    private byte[] readRelative(SecureDirectoryStream<Path> directory, Path relative, int part) throws IOException {
        Path name = relative.getName(part);
        if (part < relative.getNameCount() - 1) {
            try (var child = directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
                return readRelative(child, relative, part + 1);
            }
        }
        if (!attributes(directory, name).isRegularFile()) throw new IOException("Source is not a regular file");
        try (var channel = directory.newByteChannel(name, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            byte[] bytes = Channels.newInputStream(channel).readNBytes(8 * 1024 * 1024 + 1);
            if (bytes.length > 8 * 1024 * 1024) throw new IOException("Java source exceeds parser limit");
            return bytes;
        }
    }

    static SecureDirectoryStream<Path> openRoot(Path root) throws IOException {
        Path absolute = root.toAbsolutePath().normalize();
        DirectoryStream<Path> initial = Files.newDirectoryStream(absolute.getRoot());
        if (!(initial instanceof SecureDirectoryStream<Path> current)) {
            initial.close();
            throw new IOException("Secure directory access is unavailable");
        }
        try {
            for (Path part : absolute) {
                var next = current.newDirectoryStream(part, LinkOption.NOFOLLOW_LINKS);
                current.close();
                current = next;
            }
            return current;
        } catch (IOException | RuntimeException failure) {
            current.close();
            throw failure;
        }
    }

    private void walk(SecureDirectoryStream<Path> directory, String prefix,
                      List<SourceFile> files, List<AnalysisError> errors) throws IOException {
        List<Path> names = new ArrayList<>();
        try {
            for (Path entry : directory) names.add(entry.getFileName());
        } catch (DirectoryIteratorException failure) {
            throw failure.getCause();
        }
        names.sort(Comparator.comparing(Path::toString));
        for (Path name : names) {
            String path = prefix + name;
            if (IGNORED.contains(name.toString())) continue;
            try {
                var attributes = attributes(directory, name);
                if (attributes.isSymbolicLink()) continue;
                if (attributes.isDirectory()) {
                    try (var child = directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
                        walk(child, path + "/", files, errors);
                    }
                } else if (attributes.isRegularFile()) {
                    String filename = name.toString();
                    String type = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
                    if (filename.contains(".") && TYPES.contains(type)) {
                        SourceFile file = readFile(directory, name, path, type, attributes, errors);
                        if (file != null) files.add(file);
                    }
                }
            } catch (IOException | SecurityException failure) {
                // Exception messages may contain source data or sensitive absolute paths.
                errors.add(AnalysisError.of(path, "INVENTORY", "READ_FAILED", "File or directory could not be read safely."));
            }
        }
    }

    private SourceFile readFile(SecureDirectoryStream<Path> directory, Path name, String path,
                                String type, BasicFileAttributes before, List<AnalysisError> errors) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        long size = 0;
        boolean nul = false;
        String encoding = "UTF-8";
        byte[] prefix = new byte[2];
        int prefixSize = 0;
        try (SeekableByteChannel channel = directory.newByteChannel(name,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (channel.read(buffer) != -1) {
                buffer.flip();
                size += buffer.remaining();
                for (int i = buffer.position(); i < buffer.limit(); i++) {
                    byte value = buffer.get(i);
                    nul |= value == 0;
                    if (prefixSize < prefix.length) prefix[prefixSize++] = value;
                }
                digest.update(buffer);
                buffer.clear();
            }
            // Recognize UTF-16 BOMs before applying the NUL-byte binary heuristic.
            if (prefixSize == 2 && prefix[0] == (byte) 0xff && prefix[1] == (byte) 0xfe) encoding = "UTF-16LE";
            else if (prefixSize == 2 && prefix[0] == (byte) 0xfe && prefix[1] == (byte) 0xff) encoding = "UTF-16BE";
            if (nul && encoding.equals("UTF-8")) return null;
            channel.position(0);
            var decoder = Charset.forName(encoding).newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            var reader = new InputStreamReader(Channels.newInputStream(channel), decoder);
            try {
                char[] chars = new char[8192];
                while (reader.read(chars) != -1) { /* Validate without retaining source content. */ }
            } catch (CharacterCodingException invalid) {
                String code = encoding.equals("UTF-8") ? "INVALID_UTF8" : "INVALID_UTF16";
                encoding = "UNKNOWN";
                errors.add(AnalysisError.of(path, "ENCODING", code,
                        "Input could not be decoded; original byte hash retained and encoding is unknown."));
            }
        }
        var after = attributes(directory, name);
        if (size != before.size() || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey()) || !after.isRegularFile()) {
            errors.add(AnalysisError.of(path, "INVENTORY", "FILE_CHANGED", "File changed during inventory; omitted from this snapshot."));
            return null;
        }
        return new SourceFile(path, type.toUpperCase(Locale.ROOT), HexFormat.of().formatHex(digest.digest()), encoding, size);
    }

    static BasicFileAttributes attributes(SecureDirectoryStream<Path> directory, Path name) throws IOException {
        return directory.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
    }
}
