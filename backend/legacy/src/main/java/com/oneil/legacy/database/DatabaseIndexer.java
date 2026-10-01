package com.oneil.legacy.database;

import com.github.javaparser.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.printer.configuration.PrettyPrinterConfiguration;
import com.oneil.legacy.database.DatabaseModel.*;
import com.oneil.legacy.framework.SafeXml;
import com.oneil.legacy.scan.RepositoryInventory;
import com.oneil.legacy.scan.ScanModel.*;
import com.oneil.legacy.symbol.JavaIndexModel.*;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class DatabaseIndexer {
    public Index index(Path root, Inventory inventory, Index existing) { return new Session(root, inventory, existing).run(); }
    private record Location(String path, int line, int column, int endLine, int endColumn) {}
    private record Value(String template, boolean complete) {}
    private static final Pattern SQL_START = Pattern.compile("(?is)^\\s*(?:select|insert|update|delete|merge|with|call|exec|begin)\\b(.*)$");

    /** A SQL candidate needs a statement body; a bare task token such as "Delete" is not SQL. */
    static boolean looksLikeSql(String template) {
        var match = SQL_START.matcher(template);
        return match.matches() && !match.group(1).trim().isEmpty();
    }
    private static final Pattern HQL = Pattern.compile("(?is)^\\s*(?:select\\s+(?:distinct\\s+)?[\\w.,\\s]+\\s+)?from\\s+([\\w.$]+)(?:\\s+(?:as\\s+)?(?!where\\b)\\w+)?(?:\\s+where\\s+[\\w.$]+\\s*(?:=|<>|>=|<=|>|<)\\s*(?::\\w+|\\?|\\d+|'(?:''|[^'])*'))?\\s*$");
    private static final Set<String> JDBC_EXECUTE = Set.of("execute", "executeQuery", "executeUpdate", "executeLargeUpdate");
    private static final Set<String> TEMPLATE_EXECUTE = Set.of("query", "queryForObject", "queryForList", "queryForMap", "queryForRowSet", "update", "execute", "batchUpdate");
    private static final Set<String> QUERY_EXECUTE = Set.of("list", "uniqueResult", "getResultList", "getSingleResult", "executeUpdate", "stream", "scroll");

    private static final class Session {
        final Path root;
        final Inventory inventory;
        final List<Symbol> original;
        final Map<String, Symbol> symbols = new TreeMap<>();
        final List<Relationship> edges;
        final List<AnalysisError> errors;
        final Map<String, CompilationUnit> units = new TreeMap<>();
        final Map<String, List<VariableDeclarator>> fields = new TreeMap<>();
        final Map<String, Set<String>> entityTables = new TreeMap<>();
        final Map<String, List<String>> namedQueries = new TreeMap<>();
        final Map<MethodCallExpr, String> callQueries = new IdentityHashMap<>();
        final SqlAnalyzer sql = new SqlAnalyzer();
        Session(Path root, Inventory inventory, Index existing) {
            this.root = root; this.inventory = inventory; this.original = existing.symbols();
            original.forEach(s -> symbols.put(s.stableId(), s));
            edges = new ArrayList<>(existing.relationships()); errors = new ArrayList<>(existing.errors());
        }
        Index run() {
            for (SourceFile file : inventory.files()) if (file.relativePath().endsWith(".hbm.xml")) mapping(file);
            // All mappings are known before interpreting HQL, including forward references across XML files.
            for (var query : pendingXmlQueries) {
                try { xmlQuery(query); }
                catch (RuntimeException | StackOverflowError invalid) { error(query.at, "HIBERNATE_QUERY_ANALYSIS"); }
            }
            var parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
            for (SourceFile file : inventory.files()) if (file.fileType().equals("JAVA")) {
                try {
                    if (file.encoding().equals("UNKNOWN")) continue;
                    String source = new String(new RepositoryInventory().readVerified(root, file), Charset.forName(file.encoding()));
                    if (source.startsWith("\ufeff")) source = source.substring(1);
                    var parsed = parser.parse(source);
                    if (parsed.isSuccessful()) parsed.getResult().ifPresent(cu -> units.put(file.relativePath(), cu));
                } catch (Exception | StackOverflowError invalid) { error(new Location(file.relativePath(), 1, 1, 1, 1), "DATABASE_SOURCE_READ"); }
            }
            units.values().forEach(cu -> cu.findAll(FieldDeclaration.class).forEach(f -> f.getVariables().forEach(v -> {
                String owner = declaringType(v);
                if (owner != null) fields.computeIfAbsent(owner + "#" + v.getNameAsString(), k -> new ArrayList<>()).add(v);
            })));
            units.forEach((path, cu) -> {
                try {
                    for (VariableDeclarator variable : cu.findAll(VariableDeclarator.class)) if (variable.getInitializer().isPresent()) {
                        Value value = evaluate(variable.getInitializer().orElseThrow(), new HashSet<>(), 0);
                        if (looksLikeSql(value.template)) {
                            Location at = location(path, variable);
                            String id = query(at, "SQL", value, expression(variable.getInitializer().orElseThrow()), null);
                            edge(owner(path, variable), id, "SQL declaration", "DECLARES_QUERY", "RESOLVED", at, "JAVA_SQL");
                        }
                    }
                    for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) call(path, call);
                } catch (RuntimeException | StackOverflowError invalid) {
                    error(new Location(path, 1, 1, 1, 1), "DATABASE_SOURCE_ANALYSIS");
                }
            });
            // Preserve the entire input index, including its identities and evidence, without rewriting it.
            var added = new ArrayList<>(original);
            Set<String> prior = new HashSet<>(); original.forEach(s -> prior.add(s.stableId()));
            symbols.values().stream().filter(s -> !prior.contains(s.stableId())).forEach(added::add);
            return new Index(added, edges.stream().distinct().toList(), errors.stream().distinct().toList());
        }
        Location location(String path, Node node) {
            var range = node.getRange().orElseThrow();
            return new Location(path, range.begin.line, range.begin.column, range.end.line, range.end.column);
        }
        Location location(String path, SafeXml.Element node) { return new Location(path, node.line, node.column, node.line, node.column); }
        String owner(String path, Node node) {
            var position = node.getBegin().orElseThrow();
            return original.stream().filter(s -> s.sourcePath().equals(path) && Set.of("METHOD", "FIELD", "CLASS", "INTERFACE").contains(s.kind())
                    && (s.startLine() < position.line || s.startLine() == position.line && s.startColumn() <= position.column)
                    && (s.endLine() > position.line || s.endLine() == position.line && s.endColumn() >= position.column))
                    .min(Comparator.comparingInt((Symbol s) -> s.endLine() - s.startLine()).thenComparingInt(s -> s.endColumn() - s.startColumn()))
                    .map(Symbol::stableId).orElseGet(() -> context(path, location(path, node)));
        }
        String context(String path, Location at) {
            String id = "xml:context:" + path;
            symbol(id, "CONTEXT", path, path, null, "RESOLVED", at); return id;
        }
        void symbol(String id, String kind, String name, String qualified, String metadata, String state, Location at) {
            symbols.putIfAbsent(id, new Symbol(id, kind, name, qualified, metadata, state, at.path, at.line, at.column, at.endLine, at.endColumn));
        }
        void edge(String from, String to, String description, String type, String state, Location at, String evidence) {
            edges.add(new Relationship(from, to, description, type, to == null ? "UNRESOLVED" : state, at.path, at.line, at.column, evidence));
        }
        void error(Location at, String code) {
            errors.add(AnalysisError.of(at.path, "DATABASE", code, "Database analysis incomplete at line " + at.line + ", column " + at.column + " (" + code + ")."));
        }
        String table(String name, Location at) {
            var table = new DatabaseTable(name);
            symbol(table.stableId(), "DATABASE_TABLE", name, name, null, "RESOLVED", at);
            return table.stableId();
        }
        String query(Location at, String language, Value value, String expression, String name) {
            String id = "db:query:" + at.path + "#" + at.line + ":" + at.column;
            if (symbols.containsKey(id)) return id;
            String type = "UNKNOWN", status, state, procedure = null;
            Set<String> reads = Set.of(), writes = Set.of();
            if (!value.complete) { status = "SQL_DYNAMIC"; state = "UNRESOLVED"; }
            else if (value.template.length() > 65536) { status = "SQL_LIMIT"; state = "UNRESOLVED"; }
            else if (language.equals("HQL")) {
                try {
                    var match = HQL.matcher(value.template);
                    if (!match.matches()) { status = "HQL_UNSUPPORTED"; state = "UNRESOLVED"; }
                    else {
                        type = "SELECT";
                        var candidates = entityTables.getOrDefault(match.group(1), Set.of());
                        if (candidates.size() != 1) { status = "HQL_ENTITY_AMBIGUOUS_OR_MISSING"; state = "UNRESOLVED"; }
                        else { reads = candidates; status = "HQL_INFERRED"; state = "INFERRED"; }
                    }
                } catch (RuntimeException | StackOverflowError invalid) {
                    status = "HQL_UNSUPPORTED"; state = "UNRESOLVED"; reads = Set.of();
                }
            } else {
                var analysis = sql.analyze(value.template);
                type = analysis.statementType(); reads = analysis.reads(); writes = analysis.writes(); procedure = analysis.procedure();
                status = analysis.supported() ? "PARSED" : analysis.diagnostic();
                state = analysis.supported() ? "RESOLVED" : "UNRESOLVED";
            }
            String template = bounded(DatabaseModel.safeSql(value.template));
            var artifact = new QueryArtifact(language, type, status, bounded(expression), template,
                    Arrays.asList(template.split("<dynamic>", -1)), procedure);
            symbol(id, "QUERY_ARTIFACT", name == null ? type + "@" + at.line + ":" + at.column : name,
                    name == null ? at.path + "#" + at.line + ":" + at.column : name, artifact.metadata(), state, at);
            if (state.equals("UNRESOLVED")) error(at, status);
            for (String read : reads) edge(id, table(read, at), null, "READS_TABLE", state, at, language.equals("HQL") ? "HQL_MAPPING_HEURISTIC" : "JSQLPARSER");
            for (String write : writes) edge(id, table(write, at), null, "WRITES_TABLE", state, at, "JSQLPARSER");
            return id;
        }
        String bounded(String value) { return value.length() <= 4000 ? value : value.substring(0, 4000) + " [truncated]"; }
        String expression(Expression expression) {
            var copy = expression.clone();
            copy.findAll(StringLiteralExpr.class).forEach(s -> s.setString("<literal>"));
            copy.findAll(TextBlockLiteralExpr.class).forEach(s -> s.setValue("<literal>"));
            return copy.toString(new PrettyPrinterConfiguration().setPrintComments(false).setPrintJavadoc(false));
        }
        Value evaluate(Expression expr, Set<Node> visited, int depth) {
            if (depth > 32 || !visited.add(expr)) return new Value("<dynamic>", false);
            if (expr instanceof StringLiteralExpr string) return new Value(string.asString(), true);
            if (expr instanceof TextBlockLiteralExpr block) return new Value(block.asString(), true);
            if (expr instanceof EnclosedExpr enclosed) return evaluate(enclosed.getInner(), visited, depth + 1);
            if (expr instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.PLUS) {
                var left = evaluate(binary.getLeft(), new HashSet<>(visited), depth + 1);
                var right = evaluate(binary.getRight(), new HashSet<>(visited), depth + 1);
                String combined = left.template + right.template;
                return new Value(combined.length() > 65537 ? combined.substring(0, 65537) : combined, left.complete && right.complete);
            }
            Node declaration = declaration(expr, expr);
            if (declaration instanceof VariableDeclarator variable && stable(variable) && variable.getInitializer().isPresent())
                return evaluate(variable.getInitializer().orElseThrow(), visited, depth + 1);
            return new Value("<dynamic>", false);
        }
        boolean stable(VariableDeclarator variable) {
            Node scope = variable.findAncestor(CallableDeclaration.class).map(Node.class::cast)
                    .orElseGet(() -> variable.findCompilationUnit().orElseThrow());
            if (variable.getParentNode().orElse(null) instanceof FieldDeclaration field && !field.isFinal()) return false;
            String name = variable.getNameAsString();
            return scope.findAll(AssignExpr.class).stream().noneMatch(a -> targetName(a.getTarget()).equals(name))
                    && scope.findAll(UnaryExpr.class).stream().noneMatch(u -> Set.of(UnaryExpr.Operator.POSTFIX_INCREMENT,
                    UnaryExpr.Operator.POSTFIX_DECREMENT, UnaryExpr.Operator.PREFIX_INCREMENT, UnaryExpr.Operator.PREFIX_DECREMENT).contains(u.getOperator())
                    && targetName(u.getExpression()).equals(name));
        }
        String targetName(Expression expr) {
            if (expr instanceof NameExpr name) return name.getNameAsString();
            if (expr instanceof FieldAccessExpr field) return field.getNameAsString();
            return "";
        }
        String declaringType(Node node) { return node.findAncestor(ClassOrInterfaceDeclaration.class).flatMap(ClassOrInterfaceDeclaration::getFullyQualifiedName).orElse(null); }
        Node declaration(Expression expr, Node use) {
            if (expr instanceof EnclosedExpr enclosed) return declaration(enclosed.getInner(), use);
            String name;
            String cls = declaringType(use);
            if (expr instanceof NameExpr n) {
                name = n.getNameAsString();
                var lambda = use.findAncestor(LambdaExpr.class);
                if (lambda.isPresent()) for (Parameter p : lambda.get().getParameters())
                    if (p.getNameAsString().equals(name)) return p;
                var callable = use.findAncestor(CallableDeclaration.class);
                if (callable.isPresent()) {
                    var local = callable.get().findAll(VariableDeclarator.class).stream().filter(v -> v.getNameAsString().equals(name)
                            && v.getBegin().orElseThrow().isBefore(use.getBegin().orElseThrow())
                            && visible(v, use))
                            .sorted(Comparator.comparing((VariableDeclarator v) -> v.getBegin().orElseThrow()).reversed()).toList();
                    if (!local.isEmpty()) return local.getFirst();
                    for (Object item : callable.get().getParameters()) {
                        Parameter p = (Parameter) item;
                        if (p.getNameAsString().equals(name)) return p;
                    }
                }
            } else if (expr instanceof FieldAccessExpr field) {
                name = field.getNameAsString();
                if (!(field.getScope() instanceof ThisExpr)) cls = qualified(field.getScope().toString(), use.findCompilationUnit().orElseThrow());
            } else return null;
            var candidates = fields.getOrDefault(cls + "#" + name, List.of());
            if (candidates.size() == 1) return candidates.getFirst();
            // Explicit static imports only; wildcard or conflicting declarations never pick a constant.
            if (expr instanceof NameExpr) {
                List<VariableDeclarator> imported = new ArrayList<>();
                for (var imp : use.findCompilationUnit().orElseThrow().getImports()) if (imp.isStatic() && !imp.isAsterisk() && imp.getNameAsString().endsWith("." + name)) {
                    String value = imp.getNameAsString();
                    imported.addAll(fields.getOrDefault(value.substring(0, value.lastIndexOf('.')) + "#" + name, List.of()));
                }
                if (imported.size() == 1) return imported.getFirst();
            }
            return null;
        }
        boolean visible(VariableDeclarator variable, Node use) {
            for (Node parent = variable.getParentNode().orElse(null); parent != null; parent = parent.getParentNode().orElse(null)) {
                if (parent instanceof BlockStmt || parent instanceof ForStmt || parent instanceof ForEachStmt
                        || parent instanceof TryStmt || parent instanceof SwitchEntry || parent instanceof LambdaExpr
                        || parent instanceof CallableDeclaration<?>) return parent.isAncestorOf(use);
            }
            return false;
        }
        String qualified(String name, CompilationUnit cu) {
            name = name.replaceFirst("<.*", "");
            if (name.contains(".")) return name;
            String local = cu.getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("") + name;
            if (symbols.containsKey("java:type:" + local)) return local;
            final String simple = name;
            var imports = cu.getImports().stream().filter(i -> !i.isStatic() && !i.isAsterisk() && i.getNameAsString().endsWith("." + simple))
                    .map(ImportDeclaration::getNameAsString).distinct().toList();
            if (imports.size() == 1) return imports.getFirst();
            if (imports.size() > 1) return "";
            var wildcards = cu.getImports().stream().filter(i -> !i.isStatic() && i.isAsterisk()).toList();
            if (wildcards.size() == 1) return wildcards.getFirst().getNameAsString() + "." + name;
            return local;
        }
        String receiver(Expression expr, Node use) {
            if (expr instanceof EnclosedExpr e) return receiver(e.getInner(), use);
            if (expr instanceof CastExpr c) return qualified(c.getType().asString(), use.findCompilationUnit().orElseThrow());
            if (expr instanceof ObjectCreationExpr c) return qualified(c.getType().asString(), use.findCompilationUnit().orElseThrow());
            if (expr instanceof MethodCallExpr call && call.getScope().isPresent()) {
                String parent = receiver(call.getScope().orElseThrow(), call);
                if (parent.equals("java.sql.Connection")) {
                    if (call.getNameAsString().equals("prepareStatement")) return "java.sql.PreparedStatement";
                    if (call.getNameAsString().equals("prepareCall")) return "java.sql.CallableStatement";
                    if (call.getNameAsString().equals("createStatement")) return "java.sql.Statement";
                }
                if (parent.equals("javax.sql.DataSource") && call.getNameAsString().equals("getConnection")) return "java.sql.Connection";
                if (isSession(parent) && Set.of("createQuery", "createSQLQuery", "createNativeQuery", "getNamedQuery", "createNamedQuery").contains(call.getNameAsString())) return "org.hibernate.query.Query";
                return "";
            }
            Node declaration = declaration(expr, use);
            if (declaration instanceof VariableDeclarator v) return qualified(v.getType().asString(), use.findCompilationUnit().orElseThrow());
            if (declaration instanceof Parameter p) return qualified(p.getType().asString(), use.findCompilationUnit().orElseThrow());
            return "";
        }
        boolean isSession(String type) { return Set.of("org.hibernate.Session", "javax.persistence.EntityManager", "jakarta.persistence.EntityManager").contains(type); }
        boolean isTemplate(String type) { return Set.of("org.springframework.jdbc.core.JdbcTemplate", "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate").contains(type); }
        boolean isQuery(String type) { return Set.of("org.hibernate.Query", "org.hibernate.query.Query", "javax.persistence.Query", "jakarta.persistence.Query").contains(type); }
        String call(String path, MethodCallExpr call) {
            if (callQueries.containsKey(call)) return callQueries.get(call);
            callQueries.put(call, null);
            if (call.getScope().isEmpty()) return null;
            String receiver = receiver(call.getScope().orElseThrow(), call), method = call.getNameAsString();
            String language = "SQL", relation = "EXECUTES_QUERY";
            boolean creation = receiver.equals("java.sql.Connection") && Set.of("prepareStatement", "prepareCall").contains(method);
            boolean session = isSession(receiver) && Set.of("createQuery", "createSQLQuery", "createNativeQuery", "getNamedQuery", "createNamedQuery").contains(method);
            boolean jdbc = Set.of("java.sql.Statement", "java.sql.PreparedStatement", "java.sql.CallableStatement").contains(receiver) && (JDBC_EXECUTE.contains(method) || method.equals("addBatch"));
            boolean template = isTemplate(receiver) && TEMPLATE_EXECUTE.contains(method);
            boolean execution = isQuery(receiver) && QUERY_EXECUTE.contains(method);
            if (!(creation || session || jdbc || template || execution)) return null;
            Location at = location(path, call);
            String state = receiver.startsWith("java.sql.") ? "RESOLVED" : "INFERRED";
            String id;
            if ((jdbc || execution) && call.getArguments().isEmpty()) {
                Expression origin = call.getScope().orElseThrow();
                Node declaration = declaration(origin, call);
                if (declaration instanceof VariableDeclarator v && stable(v)) origin = v.getInitializer().orElse(origin);
                if (origin instanceof MethodCallExpr prepare) id = call(path, prepare); else id = null;
                if (id == null) id = query(at, "SQL", new Value("<dynamic>", false), expression(call.getScope().orElseThrow()), null);
            } else {
                if (call.getArguments().isEmpty()) return null;
                Expression argument = call.getArgument(0);
                Value value = evaluate(argument, new HashSet<>(), 0);
                if (session && Set.of("getNamedQuery", "createNamedQuery").contains(method)) {
                    var candidates = value.complete ? namedQueries.getOrDefault(value.template, List.of()) : List.<String>of();
                    if (candidates.size() == 1) id = candidates.getFirst();
                    else {
                        error(at, "NAMED_QUERY_AMBIGUOUS_OR_MISSING");
                        edge(owner(path, call), null, "Named query candidates=" + candidates.stream().sorted().limit(20).toList() + "; count=" + candidates.size(),
                                "DECLARES_QUERY", "UNRESOLVED", at, "JAVA_QUERY");
                        id = query(at, "HQL", new Value("<dynamic>", false), expression(argument), null);
                    }
                } else {
                    if (session && method.equals("createQuery")) language = "HQL";
                    id = query(at, language, value, expression(argument), null);
                }
            }
            if (creation || session || method.equals("addBatch")) relation = "DECLARES_QUERY";
            edge(owner(path, call), id, method, relation, state, at, "JAVA_QUERY");
            callQueries.put(call, id);
            return id;
        }

        private record XmlQuery(String owner, Location at, SafeXml.Element xml, String name) {}
        final List<XmlQuery> pendingXmlQueries = new ArrayList<>();
        void mapping(SourceFile file) {
            String path = file.relativePath();
            try {
                var doc = SafeXml.parse(new RepositoryInventory().readVerified(root, file));
                if (doc == null || !doc.name.equals("hibernate-mapping")) { error(new Location(path, 1, 1, 1, 1), "HIBERNATE_XML_ROOT"); return; }
                String ctx = context(path, location(path, doc));
                String pkg = doc.attr("package"), schema = doc.attr("schema"), catalog = doc.attr("catalog");
                for (var cls : doc.children("class")) {
                    String name = cls.attr("name");
                    if (!name.contains(".") && !pkg.isBlank()) name = pkg + "." + name;
                    String owner = symbols.containsKey("java:type:" + name) ? "java:type:" + name : ctx;
                    if (owner.equals(ctx)) error(location(path, cls), "HIBERNATE_CLASS_UNRESOLVED");
                    String classSchema = cls.attr("schema").isBlank() ? schema : cls.attr("schema");
                    String classCatalog = cls.attr("catalog").isBlank() ? catalog : cls.attr("catalog");
                    String entity = cls.attr("entity-name").isBlank() ? name.substring(name.lastIndexOf('.') + 1) : cls.attr("entity-name");
                    var tables = new ArrayList<SafeXml.Element>(); tables.add(cls);
                    for (String child : List.of("join", "set", "bag", "list", "map", "idbag")) tables.addAll(cls.children(child));
                    for (var mapped : tables) {
                        String table = mapped.attr("table");
                        if (table.contains("${")) { error(location(path, mapped), "HIBERNATE_TABLE_UNRESOLVED"); continue; }
                        if (table.isBlank()) { if (mapped == cls) error(location(path, mapped), "HIBERNATE_TABLE_UNRESOLVED"); continue; }
                        String effectiveSchema = mapped.attr("schema").isBlank() ? classSchema : mapped.attr("schema");
                        String effectiveCatalog = mapped.attr("catalog").isBlank() ? classCatalog : mapped.attr("catalog");
                        if (effectiveSchema.contains("${") || effectiveCatalog.contains("${")) {
                            error(location(path, mapped), "HIBERNATE_TABLE_UNRESOLVED"); continue;
                        }
                        String full = (effectiveCatalog.isBlank() ? "" : effectiveCatalog + ".") + (effectiveSchema.isBlank() ? "" : effectiveSchema + ".") + table;
                        String id = table(full, location(path, mapped));
                        edge(owner, id, null, "MAPS_TO_TABLE", "RESOLVED", location(path, mapped), "HIBERNATE_XML");
                        if (mapped == cls) {
                            entityTables.computeIfAbsent(name, k -> new TreeSet<>()).add(full);
                            entityTables.computeIfAbsent(entity, k -> new TreeSet<>()).add(full);
                        }
                    }
                    for (String kind : List.of("query", "sql-query")) for (var query : cls.children(kind))
                        pendingXmlQueries.add(new XmlQuery(owner, location(path, query), query, name + "." + query.attr("name")));
                }
                for (String kind : List.of("query", "sql-query")) for (var query : doc.children(kind))
                    pendingXmlQueries.add(new XmlQuery(ctx, location(path, query), query, query.attr("name")));
            } catch (Exception | StackOverflowError invalid) { error(new Location(path, 1, 1, 1, 1), "HIBERNATE_XML_PARSE"); }
        }
        void xmlQuery(XmlQuery query) {
            String text = query.xml.text.toString().trim();
            String id = query(query.at, query.xml.name.equals("query") ? "HQL" : "SQL", new Value(text, !text.contains("${")), "XML query body", query.name);
            namedQueries.computeIfAbsent(query.name, k -> new ArrayList<>()).add(id);
            edge(query.owner, id, null, "DECLARES_QUERY", "RESOLVED", query.at, "HIBERNATE_XML");
        }
    }
}
