package com.oneil.legacy.scan;

import com.oneil.legacy.symbol.JavaIndexModel.Index;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ScanModel {
    private ScanModel() {}

    public enum Status { PENDING, RUNNING, COMPLETED, FAILED }

    public record Scan(UUID id, Status status, String repositoryRoot, String analyzerVersion,
                       String gitCommitSha, Instant createdAt, Instant startedAt, Instant completedAt,
                       String failureMessage, long fileCount, long errorCount) {}
    public record SourceFile(String relativePath, String fileType, String contentHash,
                             String encoding, long sizeBytes) {
        public String stableId() { return "file:" + relativePath; }
    }
    public record AnalysisError(String relativePath, String stage, String code, String message,
                                String resolutionState) {
        public static AnalysisError of(String path, String stage, String code, String message) {
            return new AnalysisError(path, stage, code, message, "UNRESOLVED");
        }
    }
    public record Inventory(List<SourceFile> files, List<AnalysisError> errors, String gitCommitSha,
                            Index javaIndex) {
        public Inventory(List<SourceFile> files, List<AnalysisError> errors, String gitCommitSha) {
            this(files, errors, gitCommitSha, Index.empty());
        }
        public Inventory {
            files = List.copyOf(files);
            errors = List.copyOf(errors);
        }
    }
    public record ScanRequest(String repository) {}
    public record RepositoryItem(String id, String name) {}
    public record RepositoryList(List<RepositoryItem> items, long totalCount, boolean truncated) {}
    public record Page<T>(List<T> items, long totalCount, int offset, int limit, boolean truncated) {}
    public record ScanList(UUID activeScanId, Page<Scan> scans) {}
    public record ScanDetail(Scan scan, boolean active, Page<SourceFile> files, Page<AnalysisError> errors) {}
}
