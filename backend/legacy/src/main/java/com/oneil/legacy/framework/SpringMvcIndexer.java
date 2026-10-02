package com.oneil.legacy.framework;

import static com.oneil.legacy.symbol.JavaIndexModel.*;

import com.github.javaparser.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.oneil.legacy.scan.RepositoryInventory;
import com.oneil.legacy.scan.ScanModel.*;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.*;

/** Static Spring MVC annotation evidence. Target classes are never loaded or executed. */
public final class SpringMvcIndexer {
    private static final String BIND = "org.springframework.web.bind.annotation.";
    private static final Map<String, String> SHORTCUTS = Map.of(
            "GetMapping", "GET", "PostMapping", "POST", "PutMapping", "PUT",
            "DeleteMapping", "DELETE", "PatchMapping", "PATCH");
    private static final Set<String> HTTP_METHODS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE");

    public Index index(Path root, Inventory inventory, Index base) {
        return new Session(root, inventory, base).run();
    }

    private static final class Session {
        private final Path root;
        private final Inventory inventory;
        private final Map<String, Symbol> symbols = new TreeMap<>();
        private final List<Relationship> edges = new ArrayList<>();
        private final List<AnalysisError> errors = new ArrayList<>();
        private final Map<String, CompilationUnit> units = new TreeMap<>();
        private final Map<String, Constant> constants = new TreeMap<>();
        private final Map<String, Symbol> declarations = new HashMap<>();
        private final Set<String> reported = new HashSet<>();
        private record Constant(String owner, Expression value, CompilationUnit unit) {}
        private record Mapping(List<String> paths, boolean unresolvedPath, List<String> methods,
                               boolean unresolvedMethod, AnnotationExpr annotation) {}

        Session(Path root, Inventory inventory, Index base) {
            this.root = root;
            this.inventory = inventory;
            for (Symbol symbol : base.symbols()) {
                if (symbols.put(symbol.stableId(), symbol) != null) throw new IllegalArgumentException("Duplicate symbol identity");
                declarations.put(location(symbol.sourcePath(), symbol.startLine(), symbol.startColumn(), symbol.kind()), symbol);
            }
            edges.addAll(base.relationships());
            errors.addAll(base.errors());
        }

        Index run() {
            parse();
            collectConstants();
            units.forEach(this::controllers);
            return new Index(new ArrayList<>(symbols.values()), edges.stream().distinct()
                    .sorted(Comparator.comparing(Relationship::sourceId).thenComparing(Relationship::type)
                            .thenComparing(Relationship::sourcePath).thenComparingInt(Relationship::line)
                            .thenComparingInt(Relationship::column)
                            .thenComparing(e -> Objects.toString(e.targetId(), e.targetDescription())))
                    .toList(), errors.stream().distinct().toList());
        }

        private void parse() {
            var parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
            var reader = new RepositoryInventory();
            for (SourceFile file : inventory.files()) {
                if (!file.fileType().equals("JAVA") || file.encoding().equals("UNKNOWN")) continue;
                try {
                    String text = new String(reader.readVerified(root, file), Charset.forName(file.encoding()));
                    if (text.startsWith("\ufeff")) text = text.substring(1);
                    var result = parser.parse(text);
                    result.getResult().filter(unit -> result.isSuccessful()).ifPresent(unit -> units.put(file.relativePath(), unit));
                } catch (Exception | StackOverflowError ignored) {
                    // The Java indexer already records safe parse/read diagnostics for the same inventoried file.
                }
            }
        }

        private void collectConstants() {
            units.forEach((path, unit) -> {
                for (FieldDeclaration field : unit.findAll(FieldDeclaration.class)) {
                    if (!field.isStatic() || !field.isFinal()) continue;
                    var owner = field.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
                    String ownerName = qualified(owner);
                    if (ownerName == null) continue;
                    for (VariableDeclarator variable : field.getVariables()) {
                        if (!variable.getType().asString().matches("(?:java\\.lang\\.)?String") || variable.getInitializer().isEmpty()) continue;
                        constants.put(ownerName + "#" + variable.getNameAsString(),
                                new Constant(ownerName, variable.getInitializer().orElseThrow(), unit));
                    }
                }
            });
        }

        private void controllers(String path, CompilationUnit unit) {
            for (ClassOrInterfaceDeclaration controller : unit.findAll(ClassOrInterfaceDeclaration.class)) {
                boolean rest = annotation(controller, "org.springframework.web.bind.annotation.RestController").isPresent();
                if (!rest && annotation(controller, "org.springframework.stereotype.Controller").isEmpty()) continue;
                String owner = qualified(controller);
                if (owner == null || !symbols.containsKey("java:type:" + owner)) continue;
                var classMappings = mappings(controller, unit, owner, path);
                if (classMappings.isEmpty()) classMappings = List.of(new Mapping(List.of(""), false, List.of(), false, null));
                for (MethodDeclaration method : controller.getMethods()) {
                    var methodMappings = mappings(method, unit, owner, path);
                    if (methodMappings.isEmpty()) continue;
                    Symbol target = declaration(path, method, "METHOD");
                    for (Mapping classMapping : classMappings) for (Mapping methodMapping : methodMappings)
                        routes(path, owner, target, classMapping, methodMapping);
                    if (target != null && !rest && annotation(method, BIND + "ResponseBody").isEmpty())
                        views(path, method, target);
                }
            }
        }

        private List<Mapping> mappings(NodeWithAnnotations<?> node, CompilationUnit unit, String owner, String path) {
            var result = new ArrayList<Mapping>();
            for (AnnotationExpr annotation : node.getAnnotations()) {
                String kind = mappingKind(unit, annotation);
                if (kind == null) continue;
                Expression pathValue = value(annotation, "path", "value");
                var paths = pathValue == null ? Optional.of(List.of("")) : strings(pathValue, owner, unit, new HashSet<>(), 0);
                boolean unresolvedPath = paths.isEmpty();
                List<String> methods;
                boolean unresolvedMethod = false;
                if (SHORTCUTS.containsKey(kind)) methods = List.of(SHORTCUTS.get(kind));
                else {
                    Expression methodValue = value(annotation, "method");
                    if (methodValue == null) methods = List.of();
                    else {
                        var resolved = methods(methodValue, unit);
                        methods = resolved.orElse(List.of());
                        unresolvedMethod = resolved.isEmpty();
                    }
                }
                if (unresolvedPath || unresolvedMethod) diagnostic(path, annotation, "SPRING_MVC_MAPPING_UNRESOLVED");
                result.add(new Mapping(paths.orElse(List.of("<unresolved>")), unresolvedPath,
                        methods, unresolvedMethod, annotation));
            }
            return result;
        }

        private String mappingKind(CompilationUnit unit, AnnotationExpr annotation) {
            if (springAnnotation(unit, annotation, BIND + "RequestMapping")) return "RequestMapping";
            for (String name : SHORTCUTS.keySet()) if (springAnnotation(unit, annotation, BIND + name)) return name;
            return null;
        }

        private Optional<AnnotationExpr> annotation(NodeWithAnnotations<?> node, String qualified) {
            CompilationUnit unit = ((Node) node).findCompilationUnit().orElseThrow();
            return node.getAnnotations().stream().filter(a -> springAnnotation(unit, a, qualified)).findFirst();
        }

        private boolean springAnnotation(CompilationUnit unit, AnnotationExpr annotation, String qualified) {
            String written = annotation.getNameAsString();
            if (written.equals(qualified)) return true;
            String simple = qualified.substring(qualified.lastIndexOf('.') + 1);
            if (!written.equals(simple)) return false;
            String pkg = qualified.substring(0, qualified.lastIndexOf('.'));
            return unit.getImports().stream().anyMatch(imp -> !imp.isStatic()
                    && (imp.getNameAsString().equals(qualified) || imp.isAsterisk() && imp.getNameAsString().equals(pkg)));
        }

        private Expression value(AnnotationExpr annotation, String... names) {
            if (annotation instanceof SingleMemberAnnotationExpr single && Arrays.asList(names).contains("value"))
                return single.getMemberValue();
            if (annotation instanceof NormalAnnotationExpr normal) {
                for (String name : names) for (MemberValuePair pair : normal.getPairs())
                    if (pair.getNameAsString().equals(name)) return pair.getValue();
            }
            return null;
        }

        private Optional<List<String>> strings(Expression expression, String owner, CompilationUnit unit,
                                               Set<String> visiting, int depth) {
            if (expression instanceof ArrayInitializerExpr array) {
                var values = new ArrayList<String>();
                for (Expression item : array.getValues()) {
                    var value = string(item, owner, unit, new HashSet<>(visiting), depth + 1);
                    if (value.isEmpty()) return Optional.empty();
                    values.add(value.get());
                }
                return Optional.of(values);
            }
            return string(expression, owner, unit, visiting, depth).map(List::of);
        }

        private Optional<String> string(Expression expression, String owner, CompilationUnit unit,
                                        Set<String> visiting, int depth) {
            if (depth > 32) return Optional.empty();
            if (expression instanceof StringLiteralExpr literal) return Optional.of(literal.asString());
            if (expression instanceof EnclosedExpr enclosed) return string(enclosed.getInner(), owner, unit, visiting, depth + 1);
            if (expression instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.PLUS) {
                var left = string(binary.getLeft(), owner, unit, new HashSet<>(visiting), depth + 1);
                var right = string(binary.getRight(), owner, unit, new HashSet<>(visiting), depth + 1);
                return left.isPresent() && right.isPresent() ? Optional.of(left.get() + right.get()) : Optional.empty();
            }
            String key = constantKey(expression, owner, unit);
            if (key == null || !visiting.add(key)) return Optional.empty();
            Constant constant = constants.get(key);
            return constant == null ? Optional.empty() : string(constant.value, constant.owner, constant.unit, visiting, depth + 1);
        }

        private String constantKey(Expression expression, String owner, CompilationUnit unit) {
            if (expression instanceof NameExpr name) {
                String local = owner + "#" + name.getNameAsString();
                if (constants.containsKey(local)) return local;
                var imported = importedConstants(unit, name.getNameAsString());
                return imported.size() == 1 ? imported.getFirst() : null;
            }
            if (!(expression instanceof FieldAccessExpr field)) return null;
            String scope = field.getScope().toString();
            String type = resolveType(scope, owner, unit);
            return type == null ? null : type + "#" + field.getNameAsString();
        }

        private List<String> importedConstants(CompilationUnit unit, String name) {
            var matches = new TreeSet<String>();
            for (ImportDeclaration imp : unit.getImports()) {
                if (!imp.isStatic()) continue;
                String imported = imp.getNameAsString();
                if (!imp.isAsterisk() && imported.endsWith("." + name)) {
                    String key = imported.substring(0, imported.lastIndexOf('.')) + "#" + name;
                    if (constants.containsKey(key)) matches.add(key);
                } else if (imp.isAsterisk()) {
                    String key = imported + "#" + name;
                    if (constants.containsKey(key)) matches.add(key);
                }
            }
            return new ArrayList<>(matches);
        }

        private String resolveType(String written, String owner, CompilationUnit unit) {
            if (constants.keySet().stream().anyMatch(key -> key.startsWith(written + "#"))) return written;
            if (written.equals(owner.substring(owner.lastIndexOf('.') + 1))) return owner;
            for (ImportDeclaration imp : unit.getImports()) if (!imp.isStatic() && !imp.isAsterisk()
                    && imp.getNameAsString().endsWith("." + written)) return imp.getNameAsString();
            String pkg = unit.getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("");
            String local = pkg + written;
            return constants.keySet().stream().anyMatch(key -> key.startsWith(local + "#")) ? local : null;
        }

        private Optional<List<String>> methods(Expression expression, CompilationUnit unit) {
            List<Expression> values = expression instanceof ArrayInitializerExpr array
                    ? new ArrayList<>(array.getValues()) : List.of(expression);
            var methods = new ArrayList<String>();
            for (Expression value : values) {
                String written = value.toString();
                String name = written.substring(written.lastIndexOf('.') + 1);
                boolean qualified = written.startsWith("RequestMethod.") || written.startsWith(BIND + "RequestMethod.")
                        || unit.getImports().stream().anyMatch(imp -> imp.isStatic()
                        && (imp.getNameAsString().equals(BIND + "RequestMethod." + name)
                        || imp.isAsterisk() && imp.getNameAsString().equals(BIND + "RequestMethod")));
                boolean importedType = unit.getImports().stream().anyMatch(imp -> !imp.isStatic()
                        && imp.getNameAsString().equals(BIND + "RequestMethod"));
                if (!HTTP_METHODS.contains(name) || !(qualified || importedType && written.startsWith("RequestMethod.")))
                    return Optional.empty();
                methods.add(name);
            }
            return Optional.of(methods.stream().distinct().sorted().toList());
        }

        private void routes(String path, String owner, Symbol target, Mapping classMapping, Mapping methodMapping) {
            List<String> methods = combineMethods(classMapping, methodMapping);
            boolean unresolved = classMapping.unresolvedPath || methodMapping.unresolvedPath
                    || classMapping.unresolvedMethod || methodMapping.unresolvedMethod || methods == null || target == null;
            if (methods == null) {
                methods = List.of("UNRESOLVED");
                diagnostic(path, methodMapping.annotation, "SPRING_MVC_METHOD_CONFLICT");
            } else if (methods.isEmpty()) methods = List.of("UNSPECIFIED");
            for (String classPath : classMapping.paths) for (String methodPath : methodMapping.paths) for (String http : methods) {
                String routePath = combinePath(classPath, methodPath);
                AnnotationExpr at = methodMapping.annotation;
                int line = at.getBegin().map(p -> p.line).orElse(1), column = at.getBegin().map(p -> p.column).orElse(1);
                String methodId = target == null ? "unknown-method" : target.stableId();
                String id = "spring-mvc:route:" + path + "#" + http + ":" + routePath + "#" + methodId + "@" + line + ":" + column;
                String state = unresolved ? "UNRESOLVED" : "RESOLVED";
                symbols.putIfAbsent(id, symbol(id, "ROUTE", routePath, routePath,
                        "httpMethod=" + http, state, path, at));
                edges.add(relationship(id, unresolved ? null : methodId,
                        unresolved ? "controller-method=" + methodId + "; mapping unresolved" : "httpMethod=" + http,
                        "ROUTES_TO", state, path, at, "SPRING_MVC_ANNOTATION"));
            }
        }

        private List<String> combineMethods(Mapping left, Mapping right) {
            if (left.unresolvedMethod || right.unresolvedMethod) return List.of("UNRESOLVED");
            if (left.methods.isEmpty()) return right.methods;
            if (right.methods.isEmpty()) return left.methods;
            var intersection = new TreeSet<>(left.methods);
            intersection.retainAll(right.methods);
            return intersection.isEmpty() ? null : new ArrayList<>(intersection);
        }

        private String combinePath(String left, String right) {
            if (left.equals("<unresolved>") || right.equals("<unresolved>")) return "<unresolved>";
            String joined = ("/" + left + "/" + right).replaceAll("/+", "/");
            if (joined.length() > 1 && joined.endsWith("/")) joined = joined.substring(0, joined.length() - 1);
            return joined;
        }

        private void views(String path, MethodDeclaration method, Symbol source) {
            if (!method.getType().asString().matches("(?:java\\.lang\\.)?String")) return;
            var returns = method.findAll(ReturnStmt.class).stream()
                    .filter(statement -> statement.findAncestor(MethodDeclaration.class).orElse(null) == method)
                    .filter(statement -> statement.getExpression().orElse(null) instanceof StringLiteralExpr).toList();
            for (ReturnStmt statement : returns) {
                String name = statement.getExpression().orElseThrow().asStringLiteralExpr().asString();
                if (name.isBlank() || name.startsWith("redirect:") || name.startsWith("forward:")) continue;
                String id = "spring-mvc:view:" + name;
                symbols.putIfAbsent(id, symbol(id, "VIEW", name, name, null, "RESOLVED", path, statement));
                edges.add(relationship(source.stableId(), id, "logical-view=" + name, "RENDERS", "RESOLVED",
                        path, statement, "SPRING_MVC_RETURN"));
            }
        }

        private Symbol declaration(String path, Node node, String kind) {
            var begin = node.getBegin().orElse(null);
            return begin == null ? null : declarations.get(location(path, begin.line, begin.column, kind));
        }

        private String qualified(ClassOrInterfaceDeclaration type) {
            return type == null ? null : type.getFullyQualifiedName().orElse(null);
        }

        private Symbol symbol(String id, String kind, String simple, String qualified, String signature,
                              String state, String path, Node node) {
            var begin = node.getBegin().orElse(new com.github.javaparser.Position(1, 1));
            var end = node.getEnd().orElse(begin);
            return new Symbol(id, kind, simple, qualified, signature, state, path,
                    begin.line, begin.column, end.line, end.column);
        }

        private Relationship relationship(String source, String target, String description, String type,
                                          String state, String path, Node node, String evidence) {
            var begin = node.getBegin().orElse(new com.github.javaparser.Position(1, 1));
            return new Relationship(source, target, description, type, state, path, begin.line, begin.column, evidence);
        }

        private void diagnostic(String path, Node at, String code) {
            String key = path + ":" + at.getBegin().map(p -> p.line + ":" + p.column).orElse("1:1") + ":" + code;
            if (reported.add(key)) errors.add(AnalysisError.of(path, "SPRING_MVC", code,
                    "Spring MVC mapping evidence could not be fully resolved (" + code + ")."));
        }

        private static String location(String path, int line, int column, String kind) {
            return path + "\u0000" + line + "\u0000" + column + "\u0000" + kind;
        }
    }
}
