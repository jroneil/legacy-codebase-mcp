package com.oneil.legacy.database;

import java.util.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.execute.Execute;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.TablesNamesFinder;

/** Classifies syntax, not runtime effects (triggers, procedures and functions are opaque). */
public final class SqlAnalyzer {
    public record Analysis(String statementType, Set<String> reads, Set<String> writes, String procedure, String diagnostic) {
        public Analysis { reads = Collections.unmodifiableSet(new TreeSet<>(reads)); writes = Collections.unmodifiableSet(new TreeSet<>(writes)); }
        public boolean supported() { return diagnostic == null; }
    }
    public Analysis analyze(String sql) {
        if (sql.length() > 65536) return unsupported("UNKNOWN", "SQL_LIMIT");
        try {
            // JDBC escape syntax wraps CALL. Only that well-defined wrapper is removed.
            String input = sql.trim();
            if (input.matches("(?is)^\\{\\s*(?:\\?\\s*=\\s*)?call\\s+.*}$"))
                input = input.substring(1, input.length() - 1).replaceFirst("^\\s*\\?\\s*=\\s*", "");
            // parse(String) accepts a first statement with trailing SQL; require a complete, single-statement input.
            var statements = CCJSqlParserUtil.parseStatements(input, p -> p.withTimeOut(1000).withAllowedNestingDepth(100));
            if (statements == null || statements.size() != 1) return unsupported("UNKNOWN", "SQL_UNSUPPORTED");
            Statement statement = statements.getFirst();
            if (statement instanceof Execute call) {
                // Variable-based EXECUTE is not an identifiable procedure, even if its syntax parses.
                if (call.getName() == null || !call.getName().matches("[A-Za-z_][A-Za-z_0-9$]*(\\.[A-Za-z_][A-Za-z_0-9$]*)*"))
                    return unsupported("CALL", "SQL_PROCEDURE_UNRESOLVED");
                return new Analysis("CALL", Set.of(), Set.of(), call.getName(), null);
            }
            Table destination = null;
            String kind;
            if (statement instanceof Select select) {
                kind = "SELECT";
                if (select instanceof PlainSelect plain && ((plain.getIntoTables() != null && !plain.getIntoTables().isEmpty())
                        || plain.getMySqlSelectIntoClause() != null)) return unsupported(kind, "SQL_UNSUPPORTED");
                if (select.getWithItemsList() != null && select.getWithItemsList().stream().anyMatch(w -> w.getSelect() == null))
                    return unsupported(kind, "SQL_UNSUPPORTED");
            } else if (statement instanceof Insert insert) {
                kind = "INSERT"; destination = insert.getTable();
                if (destination == null || (insert.getOracleMultiInsertBranches() != null && !insert.getOracleMultiInsertBranches().isEmpty()))
                    return unsupported(kind, "SQL_UNSUPPORTED");
            } else if (statement instanceof Update update) {
                kind = "UPDATE"; destination = update.getTable();
                // Multi-target/alias updates need dialect-specific assignment analysis.
                if ((update.getStartJoins() != null && !update.getStartJoins().isEmpty()) || update.isTargetTableAlias()
                        || aliases(destination, update.getFromItem())
                        || update.getJoins() != null && update.getJoins().stream().anyMatch(j -> aliases(update.getTable(), j.getFromItem())))
                    return unsupported(kind, "SQL_UNSUPPORTED");
            } else if (statement instanceof Delete delete) {
                kind = "DELETE"; destination = delete.getTable();
                if (delete.getTables() != null && !delete.getTables().isEmpty()) return unsupported(kind, "SQL_UNSUPPORTED");
            } else if (statement instanceof Merge merge) { kind = "MERGE"; destination = merge.getTable(); }
            else return unsupported(statement.getClass().getSimpleName().toUpperCase(Locale.ROOT), "SQL_UNSUPPORTED");
            final Table target = destination;
            // Exclude only the destination AST node, not its name: INSERT INTO T SELECT FROM T both reads and writes T.
            var finder = new TableReads(target);
            finder.getTables(statement);
            Set<String> reads = finder.reads;
            return new Analysis(kind, reads, target == null ? Set.of() : Set.of(target.getFullyQualifiedName()), null, null);
        } catch (UnsupportedOperationException failure) {
            return unsupported("UNKNOWN", "SQL_UNSUPPORTED");
        } catch (Exception | StackOverflowError failure) {
            // Parser messages can include literal credentials or source fragments.
            return unsupported("UNKNOWN", "SQL_PARSE");
        }
    }
    private static boolean aliases(Table target, FromItem source) {
        return target != null && source != null && source.getAlias() != null
                && target.getFullyQualifiedName().equals(target.getName())
                && TableReads.key(target.getName()).equals(TableReads.key(source.getAlias().getName()));
    }
    /** Own scoped alias tracking avoids TablesNamesFinder's global, case-sensitive alias removal. */
    private static final class TableReads extends TablesNamesFinder<Void> {
        final Table destination;
        final Set<String> reads = new TreeSet<>();
        final Deque<Scope> scopes = new ArrayDeque<>();
        static final class Scope {
            final List<WithItem<?>> declarations;
            Set<String> visible = new HashSet<>();
            Scope(List<WithItem<?>> declarations) {
                this.declarations = declarations == null ? List.of() : declarations;
                this.declarations.forEach(w -> visible.add(key(w.getAliasName())));
            }
        }
        TableReads(Table destination) { this.destination = destination; }
        static String key(String identifier) {
            return identifier.startsWith("\"") || identifier.startsWith("`") || identifier.startsWith("[")
                    ? identifier : identifier.toLowerCase(Locale.ROOT);
        }
        Void scoped(List<WithItem<?>> declarations, java.util.function.Supplier<Void> visit) {
            scopes.push(new Scope(declarations));
            try { return visit.get(); } finally { scopes.pop(); }
        }
        @Override public <S> Void visit(Table table, S context) {
            boolean alias = table.getFullyQualifiedName().equals(table.getName())
                    && scopes.stream().anyMatch(scope -> scope.visible.contains(key(table.getName())));
            if (table != destination && !table.isTableVariable() && !alias) reads.add(table.getFullyQualifiedName());
            return super.visit(table, context);
        }
        @Override public <S> Void visit(Select select, S context) {
            return select.accept((SelectVisitor<Void>) this, context);
        }
        @Override public <S> Void visit(PlainSelect select, S context) {
            if ((select.getIntoTables() != null && !select.getIntoTables().isEmpty()) || select.getMySqlSelectIntoClause() != null)
                throw new UnsupportedOperationException();
            return scoped(select.getWithItemsList(), () -> super.visit(select, context));
        }
        @Override public <S> Void visit(ParenthesedSelect select, S context) {
            return scoped(select.getWithItemsList(), () -> super.visit(select, context));
        }
        @Override public <S> Void visit(SetOperationList select, S context) {
            return scoped(select.getWithItemsList(), () -> super.visit(select, context));
        }
        @Override public <S> Void visit(Insert insert, S context) {
            return scoped(insert.getWithItemsList(), () -> super.visit(insert, context));
        }
        @Override public <S> Void visit(Update update, S context) {
            return scoped(update.getWithItemsList(), () -> super.visit(update, context));
        }
        @Override public <S> Void visit(Delete delete, S context) {
            return scoped(delete.getWithItemsList(), () -> super.visit(delete, context));
        }
        @Override public <S> Void visit(Merge merge, S context) {
            return scoped(merge.getWithItemsList(), () -> super.visit(merge, context));
        }
        @Override public <S> Void visit(WithItem<?> with, S context) {
            if (with.getSelect() == null) throw new UnsupportedOperationException();
            Scope scope = scopes.stream().filter(s -> s.declarations.contains(with)).findFirst().orElseThrow();
            Set<String> bodyVisible = new HashSet<>();
            for (var declaration : scope.declarations) {
                if (declaration == with) {
                    if (with.isRecursive()) bodyVisible.add(key(with.getAliasName()));
                    break;
                }
                bodyVisible.add(key(declaration.getAliasName()));
            }
            var previous = scope.visible; scope.visible = bodyVisible;
            try { return super.visit(with, context); } finally { scope.visible = previous; }
        }
    }
    private Analysis unsupported(String kind, String diagnostic) { return new Analysis(kind, Set.of(), Set.of(), null, diagnostic); }
}
