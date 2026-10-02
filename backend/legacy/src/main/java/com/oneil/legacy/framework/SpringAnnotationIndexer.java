package com.oneil.legacy.framework;

import static com.oneil.legacy.symbol.JavaIndexModel.*;

import com.github.javaparser.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.*;
import com.oneil.legacy.database.DatabaseModel.DatabaseTable;
import com.oneil.legacy.scan.RepositoryInventory;
import com.oneil.legacy.scan.ScanModel.*;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.*;

/** Conservative source-only Spring component, injection, Spring Data and JPA annotation evidence. */
public final class SpringAnnotationIndexer {
    private static final String COMPONENT = "org.springframework.stereotype.";
    private static final String CONTEXT = "org.springframework.context.annotation.";
    private static final String BIND = "org.springframework.web.bind.annotation.";
    private static final String BOOT = "org.springframework.boot.autoconfigure.SpringBootApplication";
    private static final String AUTOWIRED = "org.springframework.beans.factory.annotation.Autowired";
    private static final Set<String> INJECT = Set.of("jakarta.inject.Inject", "javax.inject.Inject");
    private static final Set<String> RESOURCE = Set.of("jakarta.annotation.Resource", "javax.annotation.Resource");
    private static final Set<String> ENTITIES = Set.of("jakarta.persistence.Entity", "javax.persistence.Entity");
    private static final Set<String> TABLES = Set.of("jakarta.persistence.Table", "javax.persistence.Table");
    private static final Set<String> REPOSITORIES = Set.of(
            "org.springframework.data.repository.Repository",
            "org.springframework.data.repository.CrudRepository",
            "org.springframework.data.repository.PagingAndSortingRepository",
            "org.springframework.data.jpa.repository.JpaRepository");
    private static final List<String> STEREOTYPES = List.of(
            BOOT, CONTEXT + "Configuration", BIND + "RestController", COMPONENT + "Controller",
            COMPONENT + "Service", COMPONENT + "Repository", COMPONENT + "Component");

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
        private final Map<String, TypeDecl> types = new TreeMap<>();
        private final Map<String, Symbol> declarations = new HashMap<>();
        private final Map<String, Constant> constants = new TreeMap<>();
        private final Map<String, BeanDef> beans = new TreeMap<>();
        private final Map<String, BeanDef> componentBeans = new TreeMap<>();
        private final Map<String, String> entityTables = new TreeMap<>();
        private final List<String> scanRoots = new ArrayList<>();
        private final Set<String> applicationTypes = new TreeSet<>();
        private final Set<String> reported = new HashSet<>();

        private record TypeDecl(String path, CompilationUnit unit, ClassOrInterfaceDeclaration node, Symbol symbol) {}
        private record Constant(String owner, Expression value, CompilationUnit unit) {}
        private record BeanDef(String id, Set<String> names, String typeId, String state, boolean active,
                               String path, Node node, String origin) {}
        private record NameResult(List<String> names, boolean explicit, boolean unresolved) {}

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
            collectTypesAndConstants();
            boundaries();
            importExistingBeans();
            components();
            entities();
            repositories();
            beanMethods();
            injections();
            return new Index(new ArrayList<>(symbols.values()), edges.stream().distinct()
                    .sorted(Comparator.comparing(Relationship::sourceId).thenComparing(Relationship::type)
                            .thenComparing(Relationship::sourcePath).thenComparingInt(Relationship::line)
                            .thenComparingInt(Relationship::column)
                            .thenComparing(edge -> Objects.toString(edge.targetId(), edge.targetDescription())))
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
                    var parsed = parser.parse(text);
                    parsed.getResult().filter(unit -> parsed.isSuccessful()).ifPresent(unit -> units.put(file.relativePath(), unit));
                } catch (Exception | StackOverflowError ignored) {
                    // JavaSymbolIndexer already records a safe localized source diagnostic.
                }
            }
        }

        private void collectTypesAndConstants() {
            units.forEach((path, unit) -> {
                for (ClassOrInterfaceDeclaration node : unit.findAll(ClassOrInterfaceDeclaration.class)) {
                    String qualified = node.getFullyQualifiedName().orElse(null);
                    Symbol symbol = qualified == null ? null : symbols.get("java:type:" + qualified);
                    if (symbol != null) types.put(qualified, new TypeDecl(path, unit, node, symbol));
                    if (qualified == null) continue;
                    for (FieldDeclaration field : node.getFields()) {
                        if (!field.isStatic() || !field.isFinal()) continue;
                        for (VariableDeclarator variable : field.getVariables()) {
                            if (!variable.getType().asString().matches("(?:java\\.lang\\.)?String")
                                    || variable.getInitializer().isEmpty()) continue;
                            constants.put(qualified + "#" + variable.getNameAsString(),
                                    new Constant(qualified, variable.getInitializer().orElseThrow(), unit));
                        }
                    }
                }
            });
        }

        private void boundaries() {
            for (TypeDecl type : types.values()) {
                var boot = annotation(type.node, BOOT);
                if (boot.isPresent()) {
                    applicationTypes.add(type.symbol.stableId());
                    Expression explicit = value(boot.get(), "scanBasePackages");
                    if (explicit == null) scanRoots.add(packageName(type.symbol.qualifiedName()));
                    else {
                        var values = strings(explicit, type.symbol.qualifiedName(), type.unit, new HashSet<>(), 0);
                        if (values.isPresent()) scanRoots.addAll(values.get());
                        else diagnostic(type.path, boot.get(), "SPRING_COMPONENT_SCAN_UNRESOLVED");
                    }
                }
                var componentScan = annotation(type.node, CONTEXT + "ComponentScan");
                if (componentScan.isPresent()) {
                    Expression explicit = value(componentScan.get(), "basePackages", "value");
                    if (explicit == null) scanRoots.add(packageName(type.symbol.qualifiedName()));
                    else {
                        var values = strings(explicit, type.symbol.qualifiedName(), type.unit, new HashSet<>(), 0);
                        if (values.isPresent()) scanRoots.addAll(values.get());
                        else diagnostic(type.path, componentScan.get(), "SPRING_COMPONENT_SCAN_UNRESOLVED");
                    }
                }
            }
            var normalized = scanRoots.stream().map(String::trim).filter(value -> value.matches("(?:[A-Za-z_$][\\w$]*)(?:\\.[A-Za-z_$][\\w$]*)*") || value.isEmpty())
                    .distinct().sorted().toList();
            scanRoots.clear();
            scanRoots.addAll(normalized);
        }

        private void importExistingBeans() {
            for (Symbol symbol : new ArrayList<>(symbols.values())) {
                if (!symbol.kind().equals("BEAN")) continue;
                var targets = edges.stream().filter(edge -> edge.sourceId().equals(symbol.stableId())
                        && edge.type().equals("WIRES_TO") && edge.targetId() != null
                        && edge.targetId().startsWith("java:type:")).toList();
                if (targets.size() != 1) continue;
                var target = targets.getFirst();
                beans.putIfAbsent(symbol.stableId(), new BeanDef(symbol.stableId(), Set.of(symbol.simpleName()),
                        target.targetId(), target.resolutionState(), true, symbol.sourcePath(), null, "SPRING_XML"));
            }
        }

        private void components() {
            for (TypeDecl type : types.values()) {
                AnnotationExpr stereotype = null;
                String stereotypeName = null;
                for (String candidate : STEREOTYPES) {
                    var match = annotation(type.node, candidate);
                    if (match.isPresent()) {
                        stereotype = match.get();
                        stereotypeName = candidate.substring(candidate.lastIndexOf('.') + 1);
                        break;
                    }
                }
                if (stereotype == null) continue;
                boolean application = applicationTypes.contains(type.symbol.stableId());
                boolean active = application || inScanBoundary(type.symbol.qualifiedName());
                NameResult names = annotationNames(stereotype, type.symbol.qualifiedName(), type.unit,
                        defaultBeanName(type.node.getNameAsString()), "value", "name");
                addComponent(type, stereotype, stereotypeName, names, active);
            }
        }

        private BeanDef addComponent(TypeDecl type, Node evidence, String stereotype, NameResult names, boolean active) {
            String primary = names.names.isEmpty() ? "unresolved@" + position(evidence) : names.names.getFirst();
            String id = "spring:bean:annotation:" + type.symbol.qualifiedName() + "#" + primary;
            String state = !active || names.unresolved ? "UNRESOLVED" : names.explicit ? "RESOLVED" : "INFERRED";
            String signature = "framework=Spring Component; stereotype=" + stereotype + "; class="
                    + type.symbol.qualifiedName() + "; active=" + active + "; names=" + String.join(",", names.names);
            putSymbol(id, "BEAN", primary, type.symbol.qualifiedName() + "#" + primary, signature,
                    state, type.path, evidence);
            String target = active ? type.symbol.stableId() : null;
            String description = active ? "stereotype=" + stereotype
                    : "stereotype=" + stereotype + "; outside known component-scan boundary";
            addEdge(id, target, description, "WIRES_TO", active ? (names.explicit ? "RESOLVED" : "INFERRED") : "UNRESOLVED",
                    type.path, evidence, "SPRING_COMPONENT");
            if (!active) diagnostic(type.path, evidence, "SPRING_COMPONENT_OUTSIDE_SCAN");
            BeanDef bean = new BeanDef(id, new TreeSet<>(names.names), type.symbol.stableId(), state,
                    active && !names.unresolved, type.path, evidence, "COMPONENT");
            beans.put(id, bean);
            componentBeans.put(type.symbol.stableId(), bean);
            return bean;
        }

        private void entities() {
            for (TypeDecl type : types.values()) {
                if (annotations(type.node, ENTITIES).isEmpty()) continue;
                var table = annotations(type.node, TABLES);
                if (table.isEmpty()) continue;
                AnnotationExpr annotation = table.getFirst();
                Expression name = value(annotation, "name");
                if (name == null) {
                    diagnostic(type.path, annotation, "JPA_TABLE_NAME_MISSING");
                    continue;
                }
                var resolved = string(name, type.symbol.qualifiedName(), type.unit, new HashSet<>(), 0);
                if (resolved.isEmpty() || resolved.get().isBlank()) {
                    diagnostic(type.path, annotation, "JPA_TABLE_NAME_UNRESOLVED");
                    continue;
                }
                String tableName = resolved.get();
                var model = new DatabaseTable(tableName);
                putSymbol(model.stableId(), "DATABASE_TABLE", tableName, tableName,
                        "framework=JPA; entity=" + type.symbol.qualifiedName(), "RESOLVED", type.path, annotation);
                addEdge(type.symbol.stableId(), model.stableId(), "entity=" + type.symbol.qualifiedName(),
                        "MAPS_TO_TABLE", "RESOLVED", type.path, annotation, "JPA_ANNOTATION");
                entityTables.put(type.symbol.stableId(), model.stableId());
            }
        }

        private void repositories() {
            Map<String, String> entityByRepository = new TreeMap<>();
            boolean changed;
            do {
                changed = false;
                for (TypeDecl type : types.values()) {
                    if (!type.node.isInterface() || entityByRepository.containsKey(type.symbol.stableId())) continue;
                    for (ClassOrInterfaceType parent : type.node.getExtendedTypes()) {
                        String parentName = qualifiedTypeName(parent, type.unit);
                        String entity = null;
                        if (REPOSITORIES.contains(parentName) && !parent.getTypeArguments().orElse(new NodeList<>()).isEmpty()) {
                            entity = resolveType(parent.getTypeArguments().orElseThrow().get(0),
                                    type.symbol.qualifiedName(), type.unit);
                        } else {
                            String parentId = sourceTypeId(parentName, parent.getNameAsString(), type.unit);
                            entity = entityByRepository.get(parentId);
                        }
                        if (entity != null) {
                            entityByRepository.put(type.symbol.stableId(), entity);
                            changed = true;
                            break;
                        }
                    }
                }
            } while (changed);

            for (var entry : entityByRepository.entrySet()) {
                TypeDecl repository = types.get(entry.getKey().substring("java:type:".length()));
                if (repository == null) continue;
                String entity = entry.getValue();
                BeanDef bean = componentBeans.get(repository.symbol.stableId());
                if (bean == null) {
                    boolean active = inScanBoundary(repository.symbol.qualifiedName());
                    bean = addComponent(repository, repository.node, "SpringDataRepository",
                            new NameResult(List.of(defaultBeanName(repository.node.getNameAsString())), false, false), active);
                }
                addEdge(repository.symbol.stableId(), entity, "spring-data-entity=" + entity,
                        "WIRES_TO", "RESOLVED", repository.path, repository.node, "SPRING_DATA");
                for (MethodDeclaration method : repository.node.getMethods()) {
                    Symbol methodSymbol = declaration(repository.path, method, "METHOD");
                    if (methodSymbol == null) continue;
                    addEdge(methodSymbol.stableId(), entity, "spring-data-entity=" + entity,
                            "WIRES_TO", "RESOLVED", repository.path, method, "SPRING_DATA");
                    String table = entityTables.get(entity);
                    String access = derivedAccess(method.getNameAsString());
                    if (table != null && access != null) {
                        addEdge(methodSymbol.stableId(), table, "derived-method=" + method.getNameAsString(),
                                access, "INFERRED", repository.path, method, "SPRING_DATA_DERIVED_METHOD");
                    }
                }
            }
        }

        private String derivedAccess(String name) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.startsWith("find") || lower.startsWith("read") || lower.startsWith("get"))
                return "READS_TABLE";
            if (lower.startsWith("delete") || lower.startsWith("remove") || lower.startsWith("save"))
                return "WRITES_TABLE";
            return null;
        }

        private void beanMethods() {
            for (TypeDecl owner : types.values()) {
                var configuration = annotation(owner.node, CONTEXT + "Configuration");
                if (configuration.isEmpty()) continue;
                BeanDef configurationBean = componentBeans.get(owner.symbol.stableId());
                boolean active = configurationBean != null && configurationBean.active;
                for (MethodDeclaration method : owner.node.getMethods()) {
                    var beanAnnotation = annotation(method, CONTEXT + "Bean");
                    if (beanAnnotation.isEmpty()) continue;
                    Symbol factory = declaration(owner.path, method, "METHOD");
                    if (factory == null) continue;
                    NameResult names = annotationNames(beanAnnotation.get(), owner.symbol.qualifiedName(), owner.unit,
                            method.getNameAsString(), "name", "value");
                    String primary = names.names.isEmpty() ? "unresolved@" + position(beanAnnotation.get()) : names.names.getFirst();
                    String id = "spring:bean:annotation:" + owner.symbol.qualifiedName() + "#" + primary
                            + "@" + factory.stableId();
                    String returnType = resolveType(method.getType(), owner.symbol.qualifiedName(), owner.unit);
                    String state = !active || names.unresolved || returnType == null ? "UNRESOLVED"
                            : names.explicit ? "RESOLVED" : "INFERRED";
                    putSymbol(id, "BEAN", primary, owner.symbol.qualifiedName() + "#" + primary,
                            "framework=Spring Bean; factoryMethod=" + factory.stableId() + "; returnType="
                                    + Objects.toString(returnType, method.getTypeAsString()) + "; names=" + String.join(",", names.names),
                            state, owner.path, beanAnnotation.get());
                    if (configurationBean != null && active)
                        addEdge(configurationBean.id, id, "factoryMethod=" + factory.stableId(), "CONTAINS",
                                "RESOLVED", owner.path, beanAnnotation.get(), "SPRING_BEAN");
                    addEdge(id, active ? returnType : null,
                            returnType == null ? "returnType=" + method.getTypeAsString() : "factoryMethod=" + factory.stableId(),
                            "WIRES_TO", active && returnType != null ? "RESOLVED" : "UNRESOLVED",
                            owner.path, beanAnnotation.get(), "SPRING_BEAN");
                    BeanDef bean = new BeanDef(id, new TreeSet<>(names.names), returnType, state,
                            active && !names.unresolved && returnType != null, owner.path, beanAnnotation.get(), "BEAN_METHOD");
                    beans.put(id, bean);
                    if (!active) diagnostic(owner.path, beanAnnotation.get(), "SPRING_BEAN_OUTSIDE_SCAN");
                    else if (returnType == null) diagnostic(owner.path, beanAnnotation.get(), "SPRING_BEAN_RETURN_TYPE_UNRESOLVED");
                    else if (names.unresolved) diagnostic(owner.path, beanAnnotation.get(), "SPRING_BEAN_NAME_UNRESOLVED");
                }
            }
        }

        private void injections() {
            for (TypeDecl owner : types.values()) {
                BeanDef source = componentBeans.get(owner.symbol.stableId());
                if (source == null || !source.active) continue;
                constructors(owner, source);
                fields(owner, source);
                setters(owner, source);
            }
        }

        private void constructors(TypeDecl owner, BeanDef source) {
            var constructors = owner.node.getConstructors();
            var annotated = constructors.stream().filter(this::injectionAnnotationPresent).toList();
            ConstructorDeclaration selected = null;
            String state = "RESOLVED";
            if (annotated.size() == 1) selected = annotated.getFirst();
            else if (annotated.size() > 1) {
                unresolved(source, owner.path, owner.node, "constructor injection is ambiguous", "SPRING_CONSTRUCTOR_AMBIGUOUS");
                return;
            } else if (constructors.size() == 1) {
                selected = constructors.getFirst();
                state = "INFERRED";
            }
            if (selected == null) return;
            for (Parameter parameter : selected.getParameters()) {
                String requested = resolveType(parameter.getType(), owner.symbol.qualifiedName(), owner.unit);
                inject(source, requested, resourceName(parameter, owner), state, owner.path, parameter,
                        "constructor-parameter=" + parameter.getNameAsString());
            }
        }

        private void fields(TypeDecl owner, BeanDef source) {
            for (FieldDeclaration field : owner.node.getFields()) {
                if (!injectionAnnotationPresent(field)) continue;
                for (VariableDeclarator variable : field.getVariables()) {
                    String requested = resolveType(variable.getType(), owner.symbol.qualifiedName(), owner.unit);
                    inject(source, requested, resourceName(field, owner, variable.getNameAsString()), "RESOLVED",
                            owner.path, field, "field=" + variable.getNameAsString());
                }
            }
        }

        private void setters(TypeDecl owner, BeanDef source) {
            for (MethodDeclaration method : owner.node.getMethods()) {
                if (!injectionAnnotationPresent(method)) continue;
                if (method.getParameters().size() != 1) {
                    unresolved(source, owner.path, method, "annotated method must have one parameter",
                            "SPRING_INJECTION_METHOD_UNSUPPORTED");
                    continue;
                }
                Parameter parameter = method.getParameter(0);
                String requested = resolveType(parameter.getType(), owner.symbol.qualifiedName(), owner.unit);
                String property = method.getNameAsString().startsWith("set") && method.getNameAsString().length() > 3
                        ? defaultBeanName(method.getNameAsString().substring(3)) : parameter.getNameAsString();
                inject(source, requested, resourceName(method, owner, property), "RESOLVED",
                        owner.path, method, "method=" + method.getNameAsString());
            }
        }

        private void inject(BeanDef source, String requested, Optional<String> requestedName, String state,
                            String path, Node at, String description) {
            if (requestedName != null && requestedName.isEmpty()) {
                unresolved(source, path, at, description + "; resource name unresolved", "SPRING_RESOURCE_NAME_UNRESOLVED");
                return;
            }
            List<BeanDef> candidates = beans.values().stream().filter(bean -> bean.active)
                    .filter(bean -> requested == null || compatible(bean.typeId, requested, new HashSet<>()))
                    .filter(bean -> requestedName == null || bean.names.contains(requestedName.orElseThrow()))
                    .collect(java.util.stream.Collectors.toMap(BeanDef::id, bean -> bean, (left, right) -> left, TreeMap::new))
                    .values().stream().toList();
            if (requested == null || candidates.size() != 1) {
                String names = candidates.stream().map(BeanDef::id).sorted().reduce((a, b) -> a + ", " + b).orElse("none");
                unresolved(source, path, at, description + "; requested=" + Objects.toString(requested, "unresolved")
                        + "; candidates=[" + names + "]", candidates.isEmpty()
                                ? "SPRING_INJECTION_MISSING" : "SPRING_INJECTION_AMBIGUOUS");
                return;
            }
            BeanDef target = candidates.getFirst();
            addEdge(source.id, target.id, description + "; requested=" + requested,
                    "INJECTS", state, path, at, "SPRING_INJECTION");
            if (!requested.equals(target.typeId) && target.typeId != null)
                addEdge(requested, target.typeId, "binding=" + source.id + "; " + description,
                        "WIRES_TO", state, path, at, "SPRING_INJECTION");
        }

        private void unresolved(BeanDef source, String path, Node at, String description, String code) {
            addEdge(source.id, null, description, "INJECTS", "UNRESOLVED", path, at, "SPRING_INJECTION");
            diagnostic(path, at, code);
        }

        private boolean compatible(String candidate, String requested, Set<String> visited) {
            if (candidate == null || requested == null) return false;
            if (candidate.equals(requested)) return true;
            if (!visited.add(candidate)) return false;
            return edges.stream().filter(edge -> edge.sourceId().equals(candidate)
                            && Set.of("IMPLEMENTS", "EXTENDS").contains(edge.type())
                            && edge.targetId() != null && edge.resolutionState().equals("RESOLVED"))
                    .anyMatch(edge -> compatible(edge.targetId(), requested, visited));
        }

        private boolean injectionAnnotationPresent(NodeWithAnnotations<?> node) {
            return annotation(node, AUTOWIRED).isPresent() || INJECT.stream().anyMatch(name -> annotation(node, name).isPresent())
                    || RESOURCE.stream().anyMatch(name -> annotation(node, name).isPresent());
        }

        private Optional<String> resourceName(NodeWithAnnotations<?> node, TypeDecl owner) {
            return resourceName(node, owner, null);
        }

        /** Null means this is not Resource injection; empty means Resource name was dynamic. */
        private Optional<String> resourceName(NodeWithAnnotations<?> node, TypeDecl owner, String defaultName) {
            for (String qualified : RESOURCE) {
                var annotation = annotation(node, qualified);
                if (annotation.isEmpty()) continue;
                Expression expression = value(annotation.get(), "name");
                if (expression == null) return defaultName == null ? Optional.empty() : Optional.of(defaultName);
                return string(expression, owner.symbol.qualifiedName(), owner.unit, new HashSet<>(), 0);
            }
            return null;
        }

        private NameResult annotationNames(AnnotationExpr annotation, String owner, CompilationUnit unit,
                                           String defaultName, String... attributes) {
            Expression expression = value(annotation, attributes);
            if (expression == null) return new NameResult(List.of(defaultName), false, false);
            var names = strings(expression, owner, unit, new HashSet<>(), 0);
            if (names.isEmpty() || names.get().stream().anyMatch(String::isBlank))
                return new NameResult(List.of(), true, true);
            return new NameResult(names.get().stream().distinct().toList(), true, false);
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
            if (expression instanceof EnclosedExpr enclosed)
                return string(enclosed.getInner(), owner, unit, visiting, depth + 1);
            if (expression instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.PLUS) {
                var left = string(binary.getLeft(), owner, unit, new HashSet<>(visiting), depth + 1);
                var right = string(binary.getRight(), owner, unit, new HashSet<>(visiting), depth + 1);
                return left.isPresent() && right.isPresent() ? Optional.of(left.get() + right.get()) : Optional.empty();
            }
            String key = constantKey(expression, owner, unit);
            if (key == null || !visiting.add(key)) return Optional.empty();
            Constant constant = constants.get(key);
            return constant == null ? Optional.empty()
                    : string(constant.value, constant.owner, constant.unit, visiting, depth + 1);
        }

        private String constantKey(Expression expression, String owner, CompilationUnit unit) {
            if (expression instanceof NameExpr name) {
                String local = owner + "#" + name.getNameAsString();
                if (constants.containsKey(local)) return local;
                var imported = importedConstants(unit, name.getNameAsString());
                return imported.size() == 1 ? imported.getFirst() : null;
            }
            if (!(expression instanceof FieldAccessExpr field)) return null;
            String type = resolveQualifiedType(field.getScope().toString(), owner, unit);
            return type == null ? null : type + "#" + field.getNameAsString();
        }

        private List<String> importedConstants(CompilationUnit unit, String name) {
            var matches = new TreeSet<String>();
            for (ImportDeclaration declaration : unit.getImports()) {
                if (!declaration.isStatic()) continue;
                String imported = declaration.getNameAsString();
                String key = declaration.isAsterisk() ? imported + "#" + name
                        : imported.endsWith("." + name) ? imported.substring(0, imported.lastIndexOf('.')) + "#" + name : "";
                if (constants.containsKey(key)) matches.add(key);
            }
            return new ArrayList<>(matches);
        }

        private String resolveType(Type type, String owner, CompilationUnit unit) {
            if (!(type instanceof ClassOrInterfaceType reference)) return null;
            String qualified = qualifiedTypeName(reference, unit);
            return sourceTypeId(qualified, reference.getNameAsString(), unit);
        }

        private String sourceTypeId(String qualified, String simple, CompilationUnit unit) {
            if (qualified != null && symbols.containsKey("java:type:" + qualified)) return "java:type:" + qualified;
            if (qualified != null && qualified.contains(".")) return null;
            String local = unit.getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("") + simple;
            if (symbols.containsKey("java:type:" + local)) return "java:type:" + local;
            var candidates = symbols.values().stream().filter(symbol -> Set.of("CLASS", "INTERFACE").contains(symbol.kind())
                    && symbol.simpleName().equals(simple)).map(Symbol::stableId).distinct().sorted().toList();
            return candidates.size() == 1 ? candidates.getFirst() : null;
        }

        private String qualifiedTypeName(ClassOrInterfaceType type, CompilationUnit unit) {
            String written = type.getNameWithScope();
            if (written.contains(".") && Character.isLowerCase(written.charAt(0))) return written;
            for (ImportDeclaration declaration : unit.getImports()) {
                if (declaration.isStatic()) continue;
                if (!declaration.isAsterisk() && declaration.getNameAsString().endsWith("." + written))
                    return declaration.getNameAsString();
                if (declaration.isAsterisk()) {
                    String candidate = declaration.getNameAsString() + "." + written;
                    if (types.containsKey(candidate) || REPOSITORIES.contains(candidate)) return candidate;
                }
            }
            String local = unit.getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("") + written;
            return types.containsKey(local) ? local : written;
        }

        private String resolveQualifiedType(String written, String owner, CompilationUnit unit) {
            if (written.equals(owner.substring(owner.lastIndexOf('.') + 1))) return owner;
            if (types.containsKey(written)) return written;
            for (ImportDeclaration declaration : unit.getImports())
                if (!declaration.isStatic() && !declaration.isAsterisk()
                        && declaration.getNameAsString().endsWith("." + written))
                    return declaration.getNameAsString();
            String local = unit.getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("") + written;
            return types.containsKey(local) ? local : null;
        }

        private boolean inScanBoundary(String qualified) {
            String pkg = packageName(qualified);
            return scanRoots.stream().anyMatch(root -> root.isEmpty() || pkg.equals(root) || pkg.startsWith(root + "."));
        }

        private String packageName(String qualified) {
            int dot = qualified.lastIndexOf('.');
            return dot < 0 ? "" : qualified.substring(0, dot);
        }

        private String defaultBeanName(String simple) {
            if (simple.length() > 1 && Character.isUpperCase(simple.charAt(0)) && Character.isUpperCase(simple.charAt(1)))
                return simple;
            return simple.isEmpty() ? simple : Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
        }

        private Optional<AnnotationExpr> annotation(NodeWithAnnotations<?> node, String qualified) {
            CompilationUnit unit = ((Node) node).findCompilationUnit().orElseThrow();
            return node.getAnnotations().stream().filter(candidate -> springAnnotation(unit, candidate, qualified)).findFirst();
        }

        private List<AnnotationExpr> annotations(NodeWithAnnotations<?> node, Set<String> qualified) {
            return qualified.stream().map(name -> annotation(node, name)).flatMap(Optional::stream).toList();
        }

        private boolean springAnnotation(CompilationUnit unit, AnnotationExpr annotation, String qualified) {
            String written = annotation.getNameAsString();
            if (written.equals(qualified)) return true;
            String simple = qualified.substring(qualified.lastIndexOf('.') + 1);
            if (!written.equals(simple)) return false;
            String pkg = qualified.substring(0, qualified.lastIndexOf('.'));
            return unit.getImports().stream().anyMatch(declaration -> !declaration.isStatic()
                    && (declaration.getNameAsString().equals(qualified)
                    || declaration.isAsterisk() && declaration.getNameAsString().equals(pkg)));
        }

        private Expression value(AnnotationExpr annotation, String... names) {
            if (annotation instanceof SingleMemberAnnotationExpr single && Arrays.asList(names).contains("value"))
                return single.getMemberValue();
            if (annotation instanceof NormalAnnotationExpr normal)
                for (String name : names) for (MemberValuePair pair : normal.getPairs())
                    if (pair.getNameAsString().equals(name)) return pair.getValue();
            return null;
        }

        private Symbol declaration(String path, Node node, String kind) {
            var begin = node.getBegin().orElse(null);
            return begin == null ? null : declarations.get(location(path, begin.line, begin.column, kind));
        }

        private void putSymbol(String id, String kind, String simple, String qualified, String signature,
                               String state, String path, Node node) {
            var begin = node.getBegin().orElse(new com.github.javaparser.Position(1, 1));
            var end = node.getEnd().orElse(begin);
            symbols.putIfAbsent(id, new Symbol(id, kind, simple, qualified, signature, state, path,
                    begin.line, begin.column, end.line, end.column));
        }

        private void addEdge(String source, String target, String description, String type, String state,
                             String path, Node node, String evidence) {
            var begin = node.getBegin().orElse(new com.github.javaparser.Position(1, 1));
            edges.add(new Relationship(source, target, description, type,
                    target == null ? "UNRESOLVED" : state, path, begin.line, begin.column, evidence));
        }

        private void diagnostic(String path, Node at, String code) {
            String key = path + ":" + position(at) + ":" + code;
            if (reported.add(key)) errors.add(AnalysisError.of(path, "SPRING_ANNOTATION", code,
                    "Spring annotation evidence could not be fully resolved (" + code + ")."));
        }

        private String position(Node node) {
            return node.getBegin().map(value -> value.line + ":" + value.column).orElse("1:1");
        }

        private static String location(String path, int line, int column, String kind) {
            return path + "\u0000" + line + "\u0000" + column + "\u0000" + kind;
        }
    }
}
