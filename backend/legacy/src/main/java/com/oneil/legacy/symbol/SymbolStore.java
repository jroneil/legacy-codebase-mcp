package com.oneil.legacy.symbol;

import static com.oneil.legacy.symbol.JavaIndexModel.*;

import com.oneil.legacy.scan.ScanModel.Page;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
public class SymbolStore {
    private final JdbcTemplate jdbc;
    public SymbolStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void persist(UUID scan, Index index) {
        for (Symbol symbol : index.symbols()) {
            jdbc.update("INSERT INTO java_symbol VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", scan,
                    symbol.stableId(), symbol.kind(), symbol.simpleName(), symbol.qualifiedName(), symbol.signature(),
                    symbol.resolutionState(), symbol.sourcePath(), symbol.startLine(), symbol.startColumn(), symbol.endLine(), symbol.endColumn());
        }
        for (Relationship edge : index.relationships()) {
            UUID id = UUID.nameUUIDFromBytes(edge.toString().getBytes(StandardCharsets.UTF_8));
            jdbc.update("INSERT INTO java_relationship VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", scan, id,
                    edge.sourceId(), edge.targetId(), edge.targetDescription(), edge.type(), edge.resolutionState(),
                    edge.sourcePath(), edge.line(), edge.column(), edge.evidenceType());
        }
    }

    public record Freshness(UUID scanId, String gitCommitSha, String analyzerVersion, Instant scanCompletedAt) {}
    public record Search(Freshness freshness, Page<Symbol> candidates) {}
    public record Detail(Freshness freshness, Symbol symbol, Page<Relationship> outgoing) {}
    public record Usages(Freshness freshness, Page<Relationship> usages) {}
    public record Tables(Freshness freshness, Page<Symbol> tables) {}
    public record Location(Freshness freshness, String path, int line, Page<Symbol> symbols) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Search search(String query, int limit, int offset) {
        Freshness freshness = active();
        if (freshness == null) return new Search(null, page(List.of(), 0, limit, offset));
        // Literal substring search, not SQL wildcard matching or a single-name guess.
        String where = "scan_id=? AND (strpos(lower(simple_name), lower(?))>0 OR strpos(lower(qualified_name), lower(?))>0 OR stable_id=?)";
        long count = jdbc.queryForObject("SELECT count(*) FROM java_symbol WHERE " + where, Long.class,
                freshness.scanId(), query, query, query);
        var items = jdbc.query("SELECT * FROM java_symbol WHERE " + where + " ORDER BY stable_id LIMIT ? OFFSET ?",
                this::symbol, freshness.scanId(), query, query, query, limit, offset);
        return new Search(freshness, page(items, count, limit, offset));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Detail detail(String id, int limit, int offset) {
        Freshness freshness = requiredActive();
        Symbol symbol = find(freshness.scanId(), id);
        return new Detail(freshness, symbol, relationships(freshness.scanId(), id, false, limit, offset));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Usages usages(String id, int limit, int offset) {
        Freshness freshness = requiredActive();
        find(freshness.scanId(), id);
        return new Usages(freshness, relationships(freshness.scanId(), id, true, limit, offset));
    }

    /** Bounded listing of indexed database tables for the active completed scan. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Tables tables(int limit, int offset) {
        Freshness freshness = active();
        if (freshness == null) return new Tables(null, page(List.of(), 0, limit, offset));
        String where = "scan_id=? AND kind='DATABASE_TABLE'";
        long count = jdbc.queryForObject("SELECT count(*) FROM java_symbol WHERE " + where, Long.class, freshness.scanId());
        var items = jdbc.query("SELECT * FROM java_symbol WHERE " + where + " ORDER BY stable_id LIMIT ? OFFSET ?",
                this::symbol, freshness.scanId(), limit, offset);
        return new Tables(freshness, page(items, count, limit, offset));
    }

    /** Symbols whose indexed source range contains the repository-relative path and line; innermost span first. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Location locate(String path, int line, int limit, int offset) {
        Freshness freshness = active();
        if (freshness == null) return new Location(null, path, line, page(List.of(), 0, limit, offset));
        String where = "scan_id=? AND source_path=? AND start_line<=? AND end_line>=?";
        long count = jdbc.queryForObject("SELECT count(*) FROM java_symbol WHERE " + where, Long.class,
                freshness.scanId(), path, line, line);
        var items = jdbc.query("SELECT * FROM java_symbol WHERE " + where
                + " ORDER BY (end_line - start_line) ASC, start_line DESC, stable_id LIMIT ? OFFSET ?",
                this::symbol, freshness.scanId(), path, line, line, limit, offset);
        return new Location(freshness, path, line, page(items, count, limit, offset));
    }

    private Page<Relationship> relationships(UUID scan, String id, boolean incoming, int limit, int offset) {
        String where = "scan_id=? AND " + (incoming ? "target_id=? AND relationship_type <> 'CONTAINS'" : "source_id=?");
        long count = jdbc.queryForObject("SELECT count(*) FROM java_relationship WHERE " + where, Long.class, scan, id);
        var items = jdbc.query("SELECT * FROM java_relationship WHERE " + where
                + " ORDER BY source_path, line, column_number, relationship_type, id LIMIT ? OFFSET ?", (rs, row) ->
                new Relationship(rs.getString("source_id"), rs.getString("target_id"), rs.getString("target_description"),
                        rs.getString("relationship_type"), rs.getString("resolution_state"), rs.getString("source_path"),
                        rs.getInt("line"), rs.getInt("column_number"), rs.getString("evidence_type")), scan, id, limit, offset);
        return page(items, count, limit, offset);
    }
    private Freshness active() {
        var matches = jdbc.query("""
                SELECT s.id, s.git_commit_sha, s.analyzer_version, s.completed_at FROM active_scan a
                JOIN scan s ON s.id=a.scan_id WHERE a.singleton=true AND s.status='COMPLETED'
                """, (rs, row) -> new Freshness(rs.getObject("id", UUID.class), rs.getString("git_commit_sha"),
                rs.getString("analyzer_version"), rs.getTimestamp("completed_at").toInstant()));
        return matches.isEmpty() ? null : matches.getFirst();
    }
    private Freshness requiredActive() {
        Freshness freshness = active();
        if (freshness == null) throw new SymbolNotFoundException();
        return freshness;
    }
    private Symbol find(UUID scan, String id) {
        var matches = jdbc.query("SELECT * FROM java_symbol WHERE scan_id=? AND stable_id=?", this::symbol, scan, id);
        if (matches.isEmpty()) throw new SymbolNotFoundException();
        return matches.getFirst();
    }
    private Symbol symbol(ResultSet rs, int row) throws SQLException {
        return new Symbol(rs.getString("stable_id"), rs.getString("kind"), rs.getString("simple_name"), rs.getString("qualified_name"),
                rs.getString("signature"), rs.getString("resolution_state"), rs.getString("source_path"), rs.getInt("start_line"),
                rs.getInt("start_column"), rs.getInt("end_line"), rs.getInt("end_column"));
    }
    private <T> Page<T> page(List<T> items, long count, int limit, int offset) {
        return new Page<>(List.copyOf(items), count, offset, limit, offset + (long) items.size() < count);
    }
    public static class SymbolNotFoundException extends RuntimeException {}
}
