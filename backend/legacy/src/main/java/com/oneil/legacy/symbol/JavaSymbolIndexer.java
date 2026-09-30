package com.oneil.legacy.symbol;

import static com.oneil.legacy.symbol.JavaIndexModel.*;

import com.github.javaparser.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.github.javaparser.symbolsolver.resolution.typesolvers.*;
import com.oneil.legacy.scan.RepositoryInventory;
import com.oneil.legacy.scan.ScanModel.*;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class JavaSymbolIndexer {
    public Index index(Path root, Inventory inventory) {
        return new Analysis().run(root, inventory);
    }

    // Per-scan state: neither source ASTs nor solver caches are shared between scans.
    private static final class Analysis {
        private final MemoryTypeSolver sources = new MemoryTypeSolver();
        private final CombinedTypeSolver solver = new CombinedTypeSolver(sources, new ReflectionTypeSolver(true));
        private final Map<String, CompilationUnit> units = new TreeMap<>();
        private final Map<ClassOrInterfaceDeclaration, String> types = new IdentityHashMap<>();
        private final Map<Node, String> ids = new IdentityHashMap<>();
        private final Map<String, Symbol> symbols = new TreeMap<>();
        private final Set<String> duplicateIds = new HashSet<>();
        private final Set<String> duplicateTypes = new HashSet<>();
        private final Map<CompilationUnit, Boolean> importAmbiguity = new IdentityHashMap<>();
        private final List<Relationship> edges = new ArrayList<>();
        private final List<AnalysisError> errors = new ArrayList<>();

        Index run(Path root, Inventory inventory) {
            var parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                    .setSymbolResolver(new JavaSymbolSolver(solver)));
            var reader = new RepositoryInventory();
            for (SourceFile file : inventory.files()) {
                if (!file.fileType().equals("JAVA")) continue;
                if (file.encoding().equals("UNKNOWN")) {
                    error(file.relativePath(), "JAVA_ENCODING", "Java source has unknown encoding; semantic indexing omitted.");
                    continue;
                }
                try {
                    String text = new String(reader.readVerified(root, file), Charset.forName(file.encoding()));
                    if (text.startsWith("\ufeff")) text = text.substring(1);
                    var parsed = parser.parse(text);
                    if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
                        error(file.relativePath(), "JAVA_PARSE", "Java source could not be parsed; other files remain eligible.");
                    } else units.put(file.relativePath(), parsed.getResult().orElseThrow());
                } catch (Exception | StackOverflowError failure) {
                    error(file.relativePath(), "JAVA_READ", "Java source could not be read safely or no longer matches its inventory hash.");
                }
            }
            registerTypes();
            units.forEach((path, cu) -> {
                String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
                String pkgId = "java:package:" + pkg;
                symbols.putIfAbsent(pkgId, symbol(pkgId, "PACKAGE", pkg, pkg, null, "RESOLVED", path,
                        cu.getPackageDeclaration().map(Node.class::cast).orElse(cu)));
                for (ClassOrInterfaceDeclaration type : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                    if (!types.containsKey(type)) continue;
                    String name = types.get(type);
                    add(type, symbol("java:type:" + name, type.isInterface() ? "INTERFACE" : "CLASS",
                            type.getNameAsString(), name, null, "RESOLVED", path, type));
                    for (FieldDeclaration field : type.getFields()) {
                        for (VariableDeclarator variable : field.getVariables()) {
                            String id = "java:field:" + name + "#" + variable.getNameAsString();
                            add(variable, symbol(id, "FIELD", variable.getNameAsString(), name + "#" + variable.getNameAsString(),
                                    syntaxType(variable.getType()), "RESOLVED", path, variable));
                        }
                    }
                    for (MethodDeclaration method : type.getMethods()) {
                        String signature = signature(method);
                        String id = "java:method:" + name + "#" + signature;
                        add(method, symbol(id, "METHOD", method.getNameAsString(), name + "#" + method.getNameAsString(),
                                signature, signature.contains("?") ? "UNRESOLVED" : "RESOLVED", path, method));
                    }
                }
            });
            duplicateIds.forEach(symbols::remove);
            units.forEach((path, cu) -> relationships(path, cu));
            return new Index(new ArrayList<>(symbols.values()), edges.stream().distinct()
                    .sorted(Comparator.comparing(Relationship::sourcePath).thenComparingInt(Relationship::line)
                            .thenComparingInt(Relationship::column).thenComparing(Relationship::type)
                            .thenComparing(e -> Objects.toString(e.targetId(), e.targetDescription()))).toList(), errors);
        }

        private void registerTypes() {
            Map<String, List<ClassOrInterfaceDeclaration>> names = new TreeMap<>();
            units.forEach((path, cu) -> {
                for (TypeDeclaration<?> declaration : cu.findAll(TypeDeclaration.class)) {
                    if (!(declaration instanceof ClassOrInterfaceDeclaration type) || !memberType(type)) {
                        error(path, "JAVA_UNSUPPORTED_TYPE", "Local, anonymous, enum, record or annotation types are not indexed in this slice.");
                        continue;
                    }
                    String name = type.getFullyQualifiedName().orElseThrow();
                    names.computeIfAbsent(name, key -> new ArrayList<>()).add(type);
                }
            });
            names.forEach((name, declarations) -> {
                if (declarations.size() > 1) {
                    duplicateTypes.add(name);
                    declarations.forEach(type -> error(path(type), "JAVA_DUPLICATE_TYPE", "Duplicate qualified type name; no declaration selected."));
                } else types.put(declarations.getFirst(), name);
            });
            // A nested declaration under an excluded type must not resolve through that type.
            types.keySet().removeIf(type -> {
                for (Node parent = type.getParentNode().orElse(null); parent instanceof ClassOrInterfaceDeclaration owner;
                     parent = parent.getParentNode().orElse(null)) {
                    if (!types.containsKey(owner)) return true;
                }
                return false;
            });
            types.forEach((type, name) -> sources.addDeclaration(name, JavaParserFacade.get(solver).getTypeDeclaration(type)));
        }

        private boolean memberType(ClassOrInterfaceDeclaration type) {
            Node parent = type.getParentNode().orElse(null);
            return parent instanceof CompilationUnit || parent instanceof ClassOrInterfaceDeclaration owner && memberType(owner);
        }

        private void relationships(String path, CompilationUnit cu) {
            for (ClassOrInterfaceDeclaration type : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                String source = ids.get(type);
                if (source == null || !symbols.containsKey(source)) continue;
                String parent = type.findAncestor(ClassOrInterfaceDeclaration.class).map(ids::get)
                        .orElse("java:package:" + cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse(""));
                edge(parent, source, source, "CONTAINS", "RESOLVED", path, type, "JAVA_AST");
                for (BodyDeclaration<?> member : type.getMembers()) {
                    if (member instanceof MethodDeclaration method) {
                        edge(source, ids.get(method), null, "CONTAINS", "RESOLVED", path, method, "JAVA_AST");
                        overrides(type, method, path);
                    } else if (member instanceof FieldDeclaration field) {
                        field.getVariables().forEach(variable -> edge(source, ids.get(variable), null,
                                "CONTAINS", "RESOLVED", path, variable, "JAVA_AST"));
                    }
                }
                type.getExtendedTypes().forEach(ref -> typeEdge(source, ref, "EXTENDS", path));
                type.getImplementedTypes().forEach(ref -> typeEdge(source, ref, "IMPLEMENTS", path));
                // Imports are compilation-unit evidence associated with its top-level declared types.
                if (type.getParentNode().orElse(null) instanceof CompilationUnit) {
                    cu.getImports().forEach(imp -> {
                        String name = imp.getNameAsString();
                        if (imp.isAsterisk() || imp.isStatic()) {
                            edge(source, null, name + (imp.isAsterisk() ? ".*" : ""), "IMPORTS", "UNRESOLVED", path, imp, "JAVA_IMPORT");
                        } else {
                            try {
                                var solved = solver.tryToSolveType(name);
                                if (solved.isSolved()) edge(source, "java:type:" + solved.getCorrespondingDeclaration().getQualifiedName(),
                                        name, "IMPORTS", "RESOLVED", path, imp, "JAVA_SYMBOL_SOLVER");
                                else edge(source, null, name, "IMPORTS", "UNRESOLVED", path, imp, "JAVA_IMPORT");
                            } catch (RuntimeException | StackOverflowError failure) {
                                edge(source, null, name, "IMPORTS", "UNRESOLVED", path, imp, "JAVA_IMPORT");
                            }
                        }
                    });
                }
            }
            for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
                var owner = call.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
                if (!types.containsKey(owner) || inAnonymousBody(call)) continue;
                String source = call.findAncestor(MethodDeclaration.class).map(ids::get).orElse(ids.get(owner));
                try {
                    if (ambiguousImports(cu)) throw new IllegalStateException();
                    var target = call.resolve();
                    edge(source, methodId(target), methodId(target), "CALLS", "RESOLVED", path, call, "JAVA_SYMBOL_SOLVER");
                } catch (RuntimeException | StackOverflowError failure) {
                    // Preserve only the identifier and arity, never argument literals or source snippets.
                    edge(source, null, call.getNameAsString() + "/" + call.getArguments().size(), "CALLS",
                            "UNRESOLVED", path, call, "JAVA_AST");
                }
            }
        }

        private boolean inAnonymousBody(Node node) {
            for (Node parent = node; parent != null; parent = parent.getParentNode().orElse(null)) {
                if (parent instanceof ObjectCreationExpr creation && creation.getAnonymousClassBody().isPresent()) return true;
            }
            return false;
        }

        private void typeEdge(String source, ClassOrInterfaceType ref, String relationship, String path) {
            try {
                if (ambiguousImports(ref.findCompilationUnit().orElseThrow())) throw new IllegalStateException();
                String target = "java:type:" + ref.resolve().asReferenceType().getQualifiedName();
                edge(source, target, target, relationship, "RESOLVED", path, ref, "JAVA_SYMBOL_SOLVER");
            } catch (RuntimeException | StackOverflowError failure) {
                edge(source, null, ref.getNameWithScope(), relationship, "UNRESOLVED", path, ref, "JAVA_AST");
            }
        }

        private void overrides(ClassOrInterfaceDeclaration owner, MethodDeclaration method, String path) {
            if (method.isStatic() || method.isPrivate() || !symbols.containsKey(ids.get(method))) return;
            boolean found = false;
            try {
                var resolved = method.resolve();
                for (var ancestor : owner.resolve().getAllAncestors()) {
                    for (var candidate : ancestor.getDeclaredMethods()) {
                        var declaration = candidate.getDeclaration();
                        if (declaration.isStatic() || declaration.accessSpecifier() == com.github.javaparser.ast.AccessSpecifier.PRIVATE) continue;
                        if (declaration.accessSpecifier() == com.github.javaparser.ast.AccessSpecifier.NONE
                                && !declaration.declaringType().getPackageName().equals(resolved.declaringType().getPackageName())) continue;
                        boolean matches = declaration.getName().equals(resolved.getName())
                                && declaration.getNumberOfParams() == resolved.getNumberOfParams();
                        for (int i = 0; matches && i < declaration.getNumberOfParams(); i++) {
                            String inherited = ancestor.typeParametersMap().replaceAll(declaration.getParam(i).getType()).erasure().describe();
                            matches = inherited.equals(resolved.getParam(i).getType().erasure().describe());
                        }
                        if (declaration.toAst(MethodDeclaration.class).map(MethodDeclaration::isFinal).orElse(false)) matches = false;
                        var inheritedReturn = ancestor.typeParametersMap().replaceAll(declaration.getReturnType());
                        var actualReturn = resolved.getReturnType();
                        boolean returnMatches = inheritedReturn.describe().equals(actualReturn.describe())
                                || inheritedReturn.isReferenceType() && actualReturn.isReferenceType()
                                && inheritedReturn.isAssignableBy(actualReturn);
                        if (matches && returnMatches) {
                            String id = methodId(declaration);
                            edge(ids.get(method), id, id, "OVERRIDES", "RESOLVED", path, method, "JAVA_SYMBOL_SOLVER");
                            found = true;
                        }
                    }
                }
            } catch (RuntimeException | StackOverflowError failure) { /* Retain annotation intent below. */ }
            if (!found && method.getAnnotations().stream().anyMatch(a -> a.getNameAsString().equals("Override") || a.getNameAsString().equals("java.lang.Override"))) {
                edge(ids.get(method), null, method.getNameAsString() + "/" + method.getParameters().size(), "OVERRIDES",
                        "INFERRED", path, method, "JAVA_OVERRIDE_ANNOTATION");
            }
        }

        private String signature(MethodDeclaration method) {
            List<String> parameters = new ArrayList<>();
            for (Parameter parameter : method.getParameters()) {
                String type;
                try {
                    if (ambiguousImports(method.findCompilationUnit().orElseThrow())) throw new IllegalStateException();
                    type = parameter.getType().resolve().erasure().describe();
                } catch (RuntimeException | StackOverflowError failure) {
                    type = "?" + syntaxType(parameter.getType());
                }
                parameters.add(type + (parameter.isVarArgs() ? "[]" : ""));
            }
            return method.getNameAsString() + "(" + String.join(",", parameters) + ")";
        }

        private String syntaxType(com.github.javaparser.ast.type.Type type) {
            var copy = type.clone();
            copy.findAll(AnnotationExpr.class).forEach(Node::remove);
            return copy.asString().replaceAll("\\s+", "");
        }

        private String methodId(ResolvedMethodDeclaration method) {
            List<String> parameters = new ArrayList<>();
            for (int i = 0; i < method.getNumberOfParams(); i++) parameters.add(method.getParam(i).getType().erasure().describe());
            return "java:method:" + method.declaringType().getQualifiedName() + "#" + method.getName() + "(" + String.join(",", parameters) + ")";
        }

        private boolean ambiguousImports(CompilationUnit cu) {
            return importAmbiguity.computeIfAbsent(cu, this::checkImportAmbiguity);
        }

        private boolean checkImportAmbiguity(CompilationUnit cu) {
            Map<String, String> seen = new HashMap<>();
            for (ImportDeclaration imp : cu.getImports()) {
                if (imp.isAsterisk() || imp.isStatic()) continue;
                String name = imp.getNameAsString();
                String old = seen.putIfAbsent(name.substring(name.lastIndexOf('.') + 1), name);
                if (old != null && !old.equals(name)) return true;
            }
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("");
            for (ClassOrInterfaceType type : cu.findAll(ClassOrInterfaceType.class)) {
                if (type.getScope().isPresent() || seen.containsKey(type.getNameAsString())) continue;
                if (solver.tryToSolveType(pkg + type.getNameAsString()).isSolved()) continue;
                Set<String> candidates = new HashSet<>();
                for (ImportDeclaration imp : cu.getImports()) {
                    if (!imp.isAsterisk() || imp.isStatic()) continue;
                    String name = imp.getNameAsString() + "." + type.getNameAsString();
                    if (solver.tryToSolveType(name).isSolved()) candidates.add(name);
                }
                if (candidates.size() > 1) return true;
            }
            return false;
        }

        private void add(Node node, Symbol symbol) {
            ids.put(node, symbol.stableId());
            if (symbols.putIfAbsent(symbol.stableId(), symbol) != null) {
                duplicateIds.add(symbol.stableId());
                error(symbol.sourcePath(), "JAVA_DUPLICATE_SYMBOL", "Duplicate declaration identity; no declaration selected.");
            }
        }
        private Symbol symbol(String id, String kind, String name, String qualified, String signature, String state, String path, Node node) {
            var range = node.getRange().orElse(new Range(new Position(1, 1), new Position(1, 1)));
            return new Symbol(id, kind, name, qualified, signature, state, path, range.begin.line, range.begin.column, range.end.line, range.end.column);
        }
        private void edge(String source, String target, String description, String type, String state, String path, Node node, String evidence) {
            if (source == null || !symbols.containsKey(source) || target == null && description == null) return;
            if (target != null && !symbols.containsKey(target)) {
                description = target;
                String unresolvedTarget = target;
                if (duplicateIds.contains(target) || duplicateTypes.stream().anyMatch(name ->
                        unresolvedTarget.equals("java:type:" + name) || unresolvedTarget.startsWith("java:method:" + name + "#"))) {
                    state = "UNRESOLVED";
                }
                target = null;
            }
            var begin = node.getBegin().orElse(new Position(1, 1));
            edges.add(new Relationship(source, target, description, type, state, path, begin.line, begin.column, evidence));
        }
        private String path(Node node) {
            return units.entrySet().stream().filter(e -> e.getValue() == node.findCompilationUnit().orElse(null)).findFirst().orElseThrow().getKey();
        }
        private void error(String path, String code, String message) { errors.add(AnalysisError.of(path, "JAVA", code, message)); }
    }
}
