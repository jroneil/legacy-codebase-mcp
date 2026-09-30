package com.oneil.legacy.scan;

import com.oneil.legacy.symbol.SymbolStore;
import static com.oneil.legacy.scan.ScanModel.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ScanStore {
    private final JdbcTemplate jdbc;
    private final SymbolStore symbols;
    public ScanStore(JdbcTemplate jdbc, SymbolStore symbols) {
        this.jdbc = jdbc;
        this.symbols = symbols;
    }

    @Transactional
    public UUID create(String root, String analyzerVersion) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO scan (id, status, repository_root, analyzer_version) VALUES (?, 'PENDING', ?, ?)",
                id, root, analyzerVersion);
        return id;
    }

    @Transactional
    public void start(UUID id) {
        requireOne(jdbc.update("UPDATE scan SET status='RUNNING', started_at=clock_timestamp() WHERE id=? AND status='PENDING'", id));
    }

    @Transactional
    public void complete(UUID id, Inventory inventory) {
        // Serializes publications across application instances. Readers retain the previous pointer until commit.
        jdbc.queryForObject("SELECT singleton FROM active_scan WHERE singleton=true FOR UPDATE", Boolean.class);
        requireRunning(id);
        for (SourceFile file : inventory.files()) {
            jdbc.update("""
                    INSERT INTO source_file (scan_id, relative_path, file_type, content_hash, encoding, size_bytes)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, id, file.relativePath(), file.fileType(), file.contentHash(), file.encoding(), file.sizeBytes());
        }
        symbols.persist(id, inventory.javaIndex());
        for (AnalysisError error : inventory.errors()) {
            jdbc.update("""
                    INSERT INTO analysis_error (id, scan_id, relative_path, stage, code, message, resolution_state)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), id, error.relativePath(), error.stage(), error.code(), error.message(), error.resolutionState());
        }
        requireOne(jdbc.update("""
                UPDATE scan SET status='COMPLETED', completed_at=clock_timestamp(), git_commit_sha=?, file_count=?, error_count=?
                WHERE id=? AND status='RUNNING'
                """, inventory.gitCommitSha(), inventory.files().size(), inventory.errors().size(), id));
        requireOne(jdbc.update("UPDATE active_scan SET scan_id=? WHERE singleton=true", id));
    }

    @Transactional
    public void fail(UUID id) {
        requireOne(jdbc.update("""
                UPDATE scan SET status='FAILED', completed_at=clock_timestamp(),
                failure_message='Scan could not be completed; repository access or persistence failed.'
                WHERE id=? AND status='RUNNING'
                """, id));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ScanList list(int limit, int offset) {
        UUID active = activeId();
        long total = jdbc.queryForObject("SELECT count(*) FROM scan", Long.class);
        List<Scan> scans = jdbc.query("SELECT * FROM scan ORDER BY created_at DESC, id LIMIT ? OFFSET ?", this::scan, limit, offset);
        return new ScanList(active, page(scans, total, limit, offset));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ScanDetail detail(UUID id, int limit, int offset) {
        List<Scan> matches = jdbc.query("SELECT * FROM scan WHERE id=?", this::scan, id);
        if (matches.isEmpty()) throw new ScanNotFoundException();
        return detail(matches.getFirst(), activeId(), limit, offset);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ScanDetail active(int limit, int offset) {
        UUID active = activeId();
        if (active == null) throw new ScanNotFoundException();
        Scan scan = jdbc.queryForObject("SELECT * FROM scan WHERE id=? AND status='COMPLETED'", this::scan, active);
        return detail(scan, active, limit, offset);
    }

    private ScanDetail detail(Scan scan, UUID active, int limit, int offset) {
        boolean completed = scan.status() == Status.COMPLETED;
        List<SourceFile> files = completed ? jdbc.query("""
                SELECT * FROM source_file WHERE scan_id=? ORDER BY relative_path LIMIT ? OFFSET ?
                """, (rs, row) -> new SourceFile(rs.getString("relative_path"), rs.getString("file_type"),
                rs.getString("content_hash"), rs.getString("encoding"), rs.getLong("size_bytes")), scan.id(), limit, offset) : List.of();
        List<AnalysisError> errors = completed ? jdbc.query("""
                SELECT * FROM analysis_error WHERE scan_id=? ORDER BY relative_path NULLS FIRST, code, id LIMIT ? OFFSET ?
                """, (rs, row) -> new AnalysisError(rs.getString("relative_path"), rs.getString("stage"),
                rs.getString("code"), rs.getString("message"), rs.getString("resolution_state")), scan.id(), limit, offset) : List.of();
        return new ScanDetail(scan, scan.id().equals(active), page(files, scan.fileCount(), limit, offset),
                page(errors, scan.errorCount(), limit, offset));
    }

    private UUID activeId() {
        return jdbc.queryForObject("SELECT scan_id FROM active_scan WHERE singleton=true", UUID.class);
    }
    private void requireRunning(UUID id) {
        String state = jdbc.queryForObject("SELECT status FROM scan WHERE id=? FOR UPDATE", String.class, id);
        if (!"RUNNING".equals(state)) throw new IllegalStateException("Scan must be running");
    }
    private void requireOne(int count) {
        if (count != 1) throw new IllegalStateException("Scan transition failed");
    }
    private <T> Page<T> page(List<T> items, long total, int limit, int offset) {
        return new Page<>(List.copyOf(items), total, offset, limit, (long) offset + items.size() < total);
    }
    private Scan scan(ResultSet rs, int row) throws SQLException {
        return new Scan(rs.getObject("id", UUID.class), Status.valueOf(rs.getString("status")),
                rs.getString("repository_root"), rs.getString("analyzer_version"), rs.getString("git_commit_sha"),
                instant(rs, "created_at"), instant(rs, "started_at"), instant(rs, "completed_at"),
                rs.getString("failure_message"), rs.getLong("file_count"), rs.getLong("error_count"));
    }
    private Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
    public static class ScanNotFoundException extends RuntimeException {}
}
