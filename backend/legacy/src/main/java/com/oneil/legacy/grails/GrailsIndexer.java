package com.oneil.legacy.grails;

import com.oneil.legacy.scan.RepositoryInventory;
import com.oneil.legacy.scan.ScanModel.AnalysisError;
import com.oneil.legacy.scan.ScanModel.Inventory;
import com.oneil.legacy.scan.ScanModel.SourceFile;
import com.oneil.legacy.symbol.JavaIndexModel.Index;
import com.oneil.legacy.symbol.JavaIndexModel.Relationship;
import com.oneil.legacy.symbol.JavaIndexModel.Symbol;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.*;
import org.codehaus.groovy.ast.*;
import org.codehaus.groovy.ast.expr.*;
import org.codehaus.groovy.ast.stmt.*;
import org.codehaus.groovy.control.SourceUnit;
import org.springframework.stereotype.Component;

/**
 * Static Groovy AST evidence for a recognised Grails 3.x-6.x layout
 * ({@code grails-app/{controllers,services,domain,conf/spring}}), plus the Grails 2.x
 * {@code UrlMappings.groovy} location.
 *
 * <p>Parsing stops at the Groovy CONVERSION phase: target code is never compiled, loaded or executed.
 * Explicit source/configuration evidence is RESOLVED, documented Grails/GORM conventions are INFERRED,
 * and dynamic or metaprogrammed behavior stays UNRESOLVED. Ambiguous references return candidates
 * instead of selecting one.
 */
@Component
public class GrailsIndexer {
    public Index index(Path root, Inventory inventory, Index existing) {
        return new Session(root, inventory, existing).run();
    }

    private static final class Session {
        static final int MAX_FILES = 5000;
        static final int MAX_VISITS = 20_000;
        static final Set<String> SCALARS = Set.of("java.lang.Object", "java.lang.String", "java.lang.Integer", "java.lang.Long",
                "java.lang.Boolean", "java.lang.Double", "java.lang.Float", "java.lang.Short", "java.lang.Byte", "java.lang.Character",
                "java.lang.Number", "java.math.BigDecimal", "java.math.BigInteger", "java.util.List", "java.util.Map", "java.util.Set",
                "java.util.Collection", "java.util.Date", "java.time.LocalDate", "java.time.LocalDateTime", "groovy.lang.Closure",
                "int", "long", "boolean", "double", "float", "short", "byte", "char", "void");
        // GORM/DSL and framework-injected names are declarations, not service dependencies.
        static final Set<String> RESERVED_PROPERTIES = Set.of("mapping", "hasMany", "belongsTo", "hasOne", "constraints", "transients",
                "embedded", "mappedBy", "allowedMethods", "namespace", "log", "grailsApplication", "sessionFactory", "dataSource",
                "servletContext", "errors", "version", "id", "dateCreated", "lastUpdated");
        static final Set<String> MAPPING_DSL = Set.of("group", "namespace", "plugin");
        static final Set<String> FINDER_PREFIXES = Set.of("findBy", "findAllBy", "countBy", "getBy", "listBy");
        static final Set<String> FINDERS = Set.of("get", "read", "list", "count", "exists", "find", "findAll", "findWhere", "findAllWhere");
        static final Comparator<Relationship> ORDER = Comparator.comparing(Relationship::sourceId).thenComparing(Relationship::type)
                .thenComparing(Relationship::sourcePath).thenComparingInt(Relationship::line)
                .thenComparing(e -> Objects.toString(e.targetId(), e.targetDescription()));

        final Path root;
        final Map<String, SourceFile> files = new TreeMap<>();
        final Map<String, Symbol> symbols = new TreeMap<>();
        final List<Relationship> edges;
        final List<AnalysisError> errors;
        final Map<String, ModuleNode> modules = new TreeMap<>();
        final List<GroovyClass> classes = new ArrayList<>();
        final Map<String, List<GroovyClass>> bySimpleName = new TreeMap<>();
        final Map<String, GroovyClass> byQualifiedName = new TreeMap<>();
        final Map<String, List<GroovyMethod>> methods = new TreeMap<>();
        final Map<String, String> domainTables = new TreeMap<>();
        final Map<String, Map<String, GroovyClass>> injected = new TreeMap<>();
        final Map<String, Set<String>> unresolvedInjections = new TreeMap<>();

        record GroovyClass(String id, String qualifiedName, String simpleName, String path, ClassNode node,
                           boolean controller, boolean service, boolean domain) {}
        record GroovyMethod(String id, String name, GroovyClass owner, MethodNode node) {}
        record Table(String name, boolean explicit, String description, ASTNode at) {}

        Session(Path root, Inventory inventory, Index existing) {
            this.root = root;
            this.edges = new ArrayList<>(existing.relationships());
            this.errors = new ArrayList<>(existing.errors());
            inventory.files().forEach(f -> files.put(f.relativePath(), f));
            // A duplicate identity from an earlier stage must not be silently collapsed; publication must fail.
            existing.symbols().forEach(s -> {
                if (symbols.put(s.stableId(), s) != null) throw new IllegalArgumentException("Duplicate symbol identity");
            });
        }

        Index run() {
            parse();
            collectClasses();
            domains();
            urlMappings();
            resources();
            injectionsAndCalls();
            return new Index(new ArrayList<>(symbols.values()), edges.stream().distinct().sorted(ORDER).toList(),
                    errors.stream().distinct().toList());
        }

        // ---------- parsing ----------

        void parse() {
            var reader = new RepositoryInventory();
            int parsed = 0;
            for (SourceFile file : files.values()) {
                if (!file.fileType().equals("GROOVY")) continue;
                if (parsed++ >= MAX_FILES) { error(file.relativePath(), "GROOVY_FILE_LIMIT", "Groovy file limit reached; remaining files were not indexed."); break; }
                if (file.encoding().equals("UNKNOWN")) {
                    error(file.relativePath(), "GROOVY_ENCODING", "Groovy source has unknown encoding; semantic indexing omitted.");
                    continue;
                }
                try {
                    String text = new String(reader.readVerified(root, file), Charset.forName(file.encoding()));
                    if (text.startsWith("\ufeff")) text = text.substring(1);
                    SourceUnit unit = SourceUnit.create(file.relativePath(), text);
                    unit.parse();
                    unit.completePhase();
                    unit.nextPhase();
                    unit.convert();
                    modules.put(file.relativePath(), unit.getAST());
                } catch (Exception | StackOverflowError failure) {
                    error(file.relativePath(), "GROOVY_PARSE", "Groovy source could not be parsed; other files remain eligible.");
                }
            }
        }

        void collectClasses() {
            for (var entry : modules.entrySet()) {
                String path = entry.getKey();
                for (ClassNode node : entry.getValue().getClasses()) {
                    if (node.isScript()) continue;
                    String qualified = qualifiedName(node);
                    // Groovy reports the fully-qualified name for top-level classes and the simple name for inner classes.
                    String simple = node.getOuterClass() == null ? qualified.substring(qualified.lastIndexOf('.') + 1) : node.getName();
                    String id = "groovy:type:" + qualified;
                    if (symbols.containsKey(id)) { error(path, "GROOVY_DUPLICATE_TYPE", "Duplicate Groovy type declaration; later declaration was not indexed."); continue; }
                    boolean controller = path.startsWith("grails-app/controllers/") && simple.endsWith("Controller");
                    boolean service = path.startsWith("grails-app/services/") && simple.endsWith("Service");
                    boolean domain = path.startsWith("grails-app/domain/") || hasEntityAnnotation(node);
                    var declared = new GroovyClass(id, qualified, simple, path, node, controller, service, domain);
                    classes.add(declared);
                    bySimpleName.computeIfAbsent(simple, k -> new ArrayList<>()).add(declared);
                    byQualifiedName.put(qualified, declared);
                    symbol(id, node.isInterface() ? "INTERFACE" : "CLASS", simple, qualified, null, "RESOLVED", path, node);
                    for (FieldNode field : node.getFields()) {
                        if (field.isSynthetic()) continue;
                        symbol("groovy:field:" + qualified + "#" + field.getName(), "FIELD", field.getName(),
                                qualified + "#" + field.getName(), field.getType().getName(), "RESOLVED", path, field);
                    }
                    for (MethodNode method : node.getMethods()) {
                        if (method.getLineNumber() < 0 || method.isSynthetic()) continue;
                        if (method.getName().equals("methodMissing") || method.getName().equals("propertyMissing")) {
                            error(path, "GRAILS_METAPROGRAMMING", "Dynamic Groovy metaprogramming cannot be statically resolved.");
                        }
                        String methodId = "groovy:method:" + qualified + "#" + method.getName() + "(" + parameters(method) + ")";
                        symbol(methodId, "METHOD", method.getName(), qualified + "#" + method.getName(), signature(method), "RESOLVED", path, method);
                        methods.computeIfAbsent(qualified + "#" + method.getName(), k -> new ArrayList<>())
                                .add(new GroovyMethod(methodId, method.getName(), declared, method));
                    }
                }
            }
        }

        // ---------- GORM domain to table ----------

        void domains() {
            for (GroovyClass domain : classes) {
                if (!domain.domain()) continue;
                Table table = table(domain);
                symbol("db:table:" + table.name(), "DATABASE_TABLE", table.name(), table.name(), null, "RESOLVED", domain.path(), table.at());
                edge(domain.id(), "db:table:" + table.name(), table.description(), "MAPS_TO_TABLE",
                        table.explicit() ? "RESOLVED" : "INFERRED", domain.path(), table.at(), "GROOVY");
                domainTables.put(domain.qualifiedName(), table.name());
            }
        }

        Table table(GroovyClass domain) {
            FieldNode mapping = staticField(domain.node(), "mapping");
            if (mapping != null) {
                Expression value = mapping.getInitialValueExpression();
                Table explicit = value == null ? null : explicitTable(value);
                if (explicit != null) return explicit;
                if (!(value instanceof ClosureExpression) && !(value instanceof MapExpression)) {
                    error(domain.path(), "GRAILS_MAPPING_UNSUPPORTED", "GORM mapping form is not statically supported; conventional table name inferred.");
                }
            }
            return new Table(physicalName(domain.simpleName()), false,
                    "grails convention: domain " + domain.qualifiedName() + " maps to table " + physicalName(domain.simpleName()), domain.node());
        }

        Table explicitTable(Expression value) {
            if (value instanceof MapExpression map) {
                for (var entry : map.getMapEntryExpressions()) {
                    if (entry.getKeyExpression().getText().equals("table") && entry.getValueExpression() instanceof ConstantExpression c && c.getValue() instanceof String name) {
                        return new Table(name, true, "explicit GORM table mapping", entry);
                    }
                }
                return null;
            }
            if (!(value instanceof ClosureExpression closure) || !(closure.getCode() instanceof BlockStatement block)) return null;
            for (Statement statement : block.getStatements()) {
                if (!(statement instanceof ExpressionStatement es) || !(es.getExpression() instanceof MethodCallExpression call)) continue;
                if (!"table".equals(call.getMethodAsString())) continue;
                List<Expression> arguments = arguments(call);
                if (arguments.size() == 1 && arguments.getFirst() instanceof ConstantExpression c && c.getValue() instanceof String name) {
                    return new Table(name, true, "explicit GORM table mapping", call);
                }
            }
            return null;
        }

        /** Grails/Hibernate default physical naming: camel case to underscore-separated lower case. */
        static String physicalName(String simpleName) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < simpleName.length(); i++) {
                char c = simpleName.charAt(i);
                if (Character.isUpperCase(c) && i > 0
                        && (Character.isLowerCase(simpleName.charAt(i - 1)) || Character.isDigit(simpleName.charAt(i - 1)))) out.append('_');
                out.append(Character.toLowerCase(c));
            }
            return out.toString();
        }

        // ---------- UrlMappings ----------

        void urlMappings() {
            for (var entry : modules.entrySet()) {
                String path = entry.getKey();
                if (!path.contains("grails-app/") || !fileName(path).equals("UrlMappings.groovy")) continue;
                for (ClassNode node : entry.getValue().getClasses()) {
                    FieldNode mappings = staticField(node, "mappings");
                    if (mappings == null || !(mappings.getInitialValueExpression() instanceof ClosureExpression closure)) continue;
                    String contextId = context(path, node);
                    walkMappings(closure.getCode(), contextId, path);
                }
            }
        }

        void walkMappings(Statement statement, String contextId, String path) {
            if (statement instanceof BlockStatement block) {
                for (Statement child : block.getStatements()) walkMappings(child, contextId, path);
                return;
            }
            if (!(statement instanceof ExpressionStatement es) || !(es.getExpression() instanceof MethodCallExpression call)) return;
            mapping(call, contextId, path);
            for (Expression argument : arguments(call)) if (argument instanceof ClosureExpression nested) walkMappings(nested.getCode(), contextId, path);
        }

        void mapping(MethodCallExpression call, String contextId, String path) {
            String uri = routeText(call.getMethod());
            if (uri == null || MAPPING_DSL.contains(uri)) return;
            Map<String, Expression> named = namedArguments(call);
            String controller = stringValue(named.get("controller"));
            String action = stringValue(named.get("action"));
            String resource = stringValue(named.get("resources"));
            boolean dynamicController = named.containsKey("controller") && controller == null;
            boolean dynamicAction = named.containsKey("action") && action == null;
            String id = "grails:route:" + path + "#" + uri;
            if (symbols.containsKey(id)) id += "@" + call.getLineNumber() + ":" + call.getColumnNumber();
            String signature = controller == null && action == null ? null : "controller=" + controller + "; action=" + action;
            symbol(id, "ROUTE", uri, uri, signature, "RESOLVED", path, call);
            edge(contextId, id, null, "CONTAINS", "RESOLVED", path, call, "GROOVY");
            if (dynamicController || dynamicAction) {
                edge(id, null, "Dynamic controller/action expression cannot be statically resolved", "ROUTES_TO", "UNRESOLVED", path, call, "GROOVY");
                return;
            }
            if (resource != null) {
                List<GroovyClass> candidates = bySimpleName.getOrDefault(capitalize(resource) + "Controller", List.of());
                String target = candidates.size() == 1 ? candidates.getFirst().id() : null;
                edge(id, target, candidates.size() == 1 ? "resources mapping; RESTful actions not enumerated"
                                : candidateDescription("resources=" + resource, candidates.stream().map(GroovyClass::id).toList()),
                        "ROUTES_TO", candidates.size() == 1 ? "INFERRED" : "UNRESOLVED", path, call, "GROOVY");
                return;
            }
            if (controller == null) return;
            String controllerClass = capitalize(controller) + "Controller";
            List<GroovyClass> candidates = bySimpleName.getOrDefault(controllerClass, List.of());
            if (candidates.isEmpty()) {
                edge(id, null, "controller=" + controllerClass + " is not indexed", "ROUTES_TO", "UNRESOLVED", path, call, "GROOVY");
                return;
            }
            if (candidates.size() > 1) {
                edge(id, null, candidateDescription("controller=" + controllerClass, candidates.stream().map(GroovyClass::id).toList()),
                        "ROUTES_TO", "UNRESOLVED", path, call, "GROOVY");
                return;
            }
            GroovyClass owner = candidates.getFirst();
            // Grails default action is index when the mapping does not name one.
            String actionName = action == null ? "index" : action;
            String state = action == null ? "INFERRED" : "RESOLVED";
            List<GroovyMethod> matches = methods.getOrDefault(owner.qualifiedName() + "#" + actionName, List.of());
            if (matches.isEmpty()) {
                edge(id, owner.id(), "action=" + actionName + " is not statically indexed on " + owner.qualifiedName(),
                        "ROUTES_TO", state, path, call, "GROOVY");
                return;
            }
            edge(id, matches.size() == 1 ? matches.getFirst().id() : null,
                    matches.size() == 1 ? null : candidateDescription("action=" + actionName, matches.stream().map(GroovyMethod::id).toList()),
                    "ROUTES_TO", matches.size() == 1 ? state : "UNRESOLVED", path, call, "GROOVY");
        }

        // ---------- resources.groovy ----------

        void resources() {
            for (var entry : modules.entrySet()) {
                String path = entry.getKey();
                if (!path.contains("grails-app/conf/spring/") || !fileName(path).equals("resources.groovy")) continue;
                ClosureExpression beans = null;
                ClassNode script = null;
                for (ClassNode node : entry.getValue().getClasses()) {
                    ClosureExpression found = findBeans(node);
                    if (found != null) { beans = found; script = node; break; }
                }
                if (beans == null) {
                    error(path, "GRAILS_RESOURCES_UNSUPPORTED", "resources.groovy does not declare a beans closure; wiring was not indexed.");
                    continue;
                }
                String contextId = context(path, script);
                Map<String, String> beanIds = new TreeMap<>();
                List<MethodCallExpression> definitions = new ArrayList<>();
                for (Statement statement : statements(beans.getCode())) {
                    if (!(statement instanceof ExpressionStatement es) || !(es.getExpression() instanceof MethodCallExpression call)) continue;
                    String name = call.getMethodAsString();
                    if (name == null) continue;
                    definitions.add(call);
                    String beanId = "spring:bean:" + path + "#" + name;
                    if (symbols.containsKey(beanId)) beanId += "@" + call.getLineNumber() + ":" + call.getColumnNumber();
                    beanIds.put(name, beanId);
                    String className = classNameArgument(call, path);
                    symbol(beanId, "BEAN", name, path + "#" + name, className, "RESOLVED", path, call);
                    edge(contextId, beanId, null, "CONTAINS", "RESOLVED", path, call, "GROOVY");
                    List<GroovyClass> candidates = className == null ? List.of() : classCandidates(className);
                    String wired = candidates.size() == 1 ? candidates.getFirst().id() : javaTypeId(className);
                    edge(beanId, candidates.size() <= 1 ? wired : null,
                            candidates.size() <= 1 ? className : candidateDescription("class=" + className, candidates.stream().map(GroovyClass::id).toList()),
                            "WIRES_TO", candidates.size() <= 1 && wired != null ? "RESOLVED" : "UNRESOLVED", path, call, "GROOVY");
                }
                for (MethodCallExpression call : definitions) wireBean(call, beanIds, path);
            }
        }

        void wireBean(MethodCallExpression call, Map<String, String> beanIds, String path) {
            List<Expression> arguments = arguments(call);
            Expression body = arguments.size() == 2 ? arguments.get(1) : arguments.size() == 1 && arguments.getFirst() instanceof ClosureExpression ? arguments.getFirst() : null;
            if (!(body instanceof ClosureExpression closure)) return;
            String beanId = beanIds.get(call.getMethodAsString());
            if (beanId == null) return;
            for (Statement statement : statements(closure.getCode())) {
                if (!(statement instanceof ExpressionStatement es)) continue;
                Expression expression = es.getExpression();
                if (expression instanceof BinaryExpression binary && binary.getLeftExpression() instanceof VariableExpression property) {
                    Expression target = binary.getRightExpression();
                    edge(beanId, resolveBeanRef(target, beanIds), "property=" + property.getName() + "; " + describeRef(target),
                            "INJECTS", "RESOLVED", path, binary, "GROOVY");
                } else if (expression instanceof MapEntryExpression entry) {
                    Expression target = entry.getValueExpression();
                    edge(beanId, resolveBeanRef(target, beanIds), "property=" + entry.getKeyExpression().getText() + "; " + describeRef(target),
                            "INJECTS", "RESOLVED", path, entry, "GROOVY");
                }
            }
        }

        String resolveBeanRef(Expression expression, Map<String, String> beanIds) {
            if (expression instanceof MethodCallExpression call && "ref".equals(call.getMethodAsString())) {
                List<Expression> arguments = arguments(call);
                if (arguments.size() == 1) return beanIds.get(stringValue(arguments.getFirst()));
                return null;
            }
            if (expression instanceof VariableExpression variable) return beanIds.get(variable.getName());
            return null;
        }

        String describeRef(Expression expression) {
            if (expression instanceof MethodCallExpression call && "ref".equals(call.getMethodAsString())) {
                List<Expression> arguments = arguments(call);
                return "ref=" + (arguments.size() == 1 ? stringValue(arguments.getFirst()) : "unsupported");
            }
            if (expression instanceof VariableExpression variable) return "bean-ref=" + variable.getName();
            return "unsupported bean reference";
        }

        String classNameArgument(MethodCallExpression call, String path) {
            List<Expression> arguments = arguments(call);
            if (arguments.isEmpty()) return null;
            Expression first = arguments.getFirst();
            if (first instanceof ClassExpression type) return type.getType().getName();
            if (first instanceof VariableExpression variable && !variable.getName().isEmpty()
                    && Character.isUpperCase(variable.getName().charAt(0))) return variable.getName();
            return null;
        }

        // ---------- injection and call resolution ----------

        void injectionsAndCalls() {
            for (GroovyClass owner : classes) {
                if (!owner.controller() && !owner.service()) continue;
                Map<String, GroovyClass> resolved = new TreeMap<>();
                Set<String> unresolved = new TreeSet<>();
                for (PropertyNode property : owner.node().getProperties()) {
                    if (property.isStatic() || RESERVED_PROPERTIES.contains(property.getName())) continue;
                    String type = property.getType().getName();
                    List<GroovyClass> candidates = candidateDependencies(property.getName(), type);
                    if (candidates.isEmpty()) {
                        if (conventionalDependency(property.getName(), type)) {
                            unresolved.add(property.getName());
                            edge(owner.id(), null, "property=" + property.getName() + " is not indexed", "INJECTS", "UNRESOLVED",
                                    owner.path(), property, "GROOVY");
                        }
                        continue;
                    }
                    if (candidates.size() > 1) {
                        unresolved.add(property.getName());
                        edge(owner.id(), null, candidateDescription("property=" + property.getName(), candidates.stream().map(GroovyClass::id).toList()),
                                "INJECTS", "UNRESOLVED", owner.path(), property, "GROOVY");
                        continue;
                    }
                    GroovyClass target = candidates.getFirst();
                    resolved.put(property.getName(), target);
                    boolean explicit = !type.equals("java.lang.Object") && !SCALARS.contains(type);
                    edge(owner.id(), target.id(), "property=" + property.getName() + "; " + (explicit ? "declared type=" + type : "grails convention name"),
                            "INJECTS", explicit ? "RESOLVED" : "INFERRED", owner.path(), property, "GROOVY");
                }
                injected.put(owner.qualifiedName(), resolved);
                unresolvedInjections.put(owner.qualifiedName(), unresolved);
            }
            for (Map.Entry<String, Map<String, GroovyClass>> entry : injected.entrySet()) {
                GroovyClass owner = byQualifiedName.get(entry.getKey());
                if (owner == null) continue;
                for (MethodNode method : owner.node().getMethods()) {
                    if (method.getLineNumber() < 0 || method.isSynthetic() || method.getCode() == null) continue;
                    GroovyMethod declared = method(owner, method);
                    if (declared == null) continue;
                    method.getCode().visit(new CallVisitor(declared, entry.getValue(), unresolvedInjections.getOrDefault(entry.getKey(), Set.of())));
                }
            }
        }

        List<GroovyClass> candidateDependencies(String propertyName, String type) {
            if (!type.equals("java.lang.Object") && SCALARS.contains(type)) return List.of();
            if (!type.equals("java.lang.Object")) return classCandidates(type);
            return bySimpleName.getOrDefault(capitalize(propertyName), List.of());
        }

        /** True when the name or declared type follows the Grails service/DAO naming convention. */
        static boolean conventionalDependency(String propertyName, String type) {
            return (type.equals("java.lang.Object") ? propertyName : type).matches(".*(?:Service|Dao|Repository)");
        }

        /** Classes that a declared Groovy type or a resources.groovy bean class may refer to. */
        List<GroovyClass> classCandidates(String typeName) {
            GroovyClass exact = byQualifiedName.get(typeName);
            if (exact != null) return List.of(exact);
            String simple = typeName.contains(".") ? typeName.substring(typeName.lastIndexOf('.') + 1) : typeName;
            return bySimpleName.getOrDefault(simple, List.of());
        }

        /** A Java symbol indexed by the Java analyzer, used when no Groovy class matches. */
        String javaTypeId(String typeName) {
            if (typeName == null) return null;
            if (symbols.containsKey("java:type:" + typeName)) return "java:type:" + typeName;
            String simple = typeName.contains(".") ? typeName.substring(typeName.lastIndexOf('.') + 1) : typeName;
            return symbols.containsKey("java:type:" + simple) ? "java:type:" + simple : null;
        }

        final class CallVisitor extends CodeVisitorSupport {
            final GroovyMethod owner;
            final Map<String, GroovyClass> resolved;
            final Set<String> unresolved;
            int visits;

            CallVisitor(GroovyMethod owner, Map<String, GroovyClass> resolved, Set<String> unresolved) {
                this.owner = owner;
                this.resolved = resolved;
                this.unresolved = unresolved;
            }

            @Override public void visitMethodCallExpression(MethodCallExpression call) {
                if (visits++ > MAX_VISITS) return;
                analyse(call);
                super.visitMethodCallExpression(call);
            }

            @Override public void visitPropertyExpression(PropertyExpression expression) {
                if (visits++ > MAX_VISITS) return;
                if (expression.getText().contains("metaClass")) metaprogramming(expression);
                super.visitPropertyExpression(expression);
            }

            void analyse(MethodCallExpression call) {
                String name = call.getMethodAsString();
                if (name == null) return;
                if (name.equals("methodMissing") || name.equals("propertyMissing") || name.equals("invokeMethod")) {
                    metaprogramming(call);
                    return;
                }
                Expression receiver = call.getObjectExpression();
                if (receiver instanceof VariableExpression variable) {
                    GroovyClass service = resolved.get(variable.getName());
                    if (service != null) { callOn(service, name, call); return; }
                    if (unresolved.contains(variable.getName())) {
                        edge(owner.id(), null, "injected " + variable.getName() + " is ambiguous or missing; " + name + " not resolved",
                                "CALLS", "UNRESOLVED", owner.owner().path(), call, "GROOVY");
                        return;
                    }
                    GroovyClass domain = Character.isUpperCase(variable.getName().charAt(0)) ? bySimpleName.getOrDefault(variable.getName(), List.of()).stream().filter(GroovyClass::domain).findFirst().orElse(null) : null;
                    if (domain != null) finder(domain, name, call);
                    return;
                }
                if (receiver instanceof ClassExpression type) {
                    GroovyClass target = classCandidates(type.getType().getName()).stream().filter(GroovyClass::domain).findFirst().orElse(null);
                    if (target != null) finder(target, name, call);
                }
            }

            void callOn(GroovyClass target, String name, MethodCallExpression call) {
                List<GroovyMethod> candidates = methods.getOrDefault(target.qualifiedName() + "#" + name, List.of());
                if (candidates.isEmpty()) {
                    edge(owner.id(), null, "method " + target.qualifiedName() + "#" + name + " is not statically indexed",
                            "CALLS", "UNRESOLVED", owner.owner().path(), call, "GROOVY");
                    return;
                }
                if (candidates.size() > 1) {
                    int arity = argumentCount(call);
                    List<GroovyMethod> byArity = candidates.stream().filter(m -> m.node().getParameters().length == arity).toList();
                    if (!byArity.isEmpty()) candidates = byArity;
                }
                edge(owner.id(), candidates.size() == 1 ? candidates.getFirst().id() : null,
                        candidates.size() == 1 ? null : candidateDescription("method=" + name, candidates.stream().map(GroovyMethod::id).toList()),
                        "CALLS", candidates.size() == 1 ? "RESOLVED" : "UNRESOLVED", owner.owner().path(), call, "GROOVY");
            }

            /** GORM dynamic finder on a statically determined domain: a documented convention, so INFERRED. */
            void finder(GroovyClass domain, String name, MethodCallExpression call) {
                if (!isReadFinder(name)) return;
                String table = domainTables.get(domain.qualifiedName());
                if (table == null) return;
                String description = "gorm finder " + name + "; domain=" + domain.qualifiedName() + "; table=" + table;
                edge(owner.id(), "db:table:" + table, description, "READS_TABLE", "INFERRED", owner.owner().path(), call, "GROOVY");
                edge(owner.id(), domain.id(), description, "CALLS", "INFERRED", owner.owner().path(), call, "GROOVY");
            }

            void metaprogramming(ASTNode at) {
                errors.add(AnalysisError.of(owner.owner().path(), "GROOVY", "GRAILS_METAPROGRAMMING",
                        "Dynamic Groovy metaprogramming cannot be statically resolved."));
                edge(owner.id(), null, "metaprogrammed target is not statically resolvable", "CALLS", "UNRESOLVED",
                        owner.owner().path(), at, "GROOVY");
            }
        }

        static boolean isReadFinder(String name) {
            if (FINDERS.contains(name)) return true;
            return FINDER_PREFIXES.stream().anyMatch(prefix -> name.startsWith(prefix) && name.length() > prefix.length());
        }

        static int argumentCount(MethodCallExpression call) {
            return call.getArguments() instanceof TupleExpression tuple ? tuple.getExpressions().size() : 1;
        }

        GroovyMethod method(GroovyClass owner, MethodNode node) {
            List<GroovyMethod> matches = methods.getOrDefault(owner.qualifiedName() + "#" + node.getName(), List.of());
            for (GroovyMethod candidate : matches) if (candidate.node() == node) return candidate;
            return null;
        }

        // ---------- shared helpers ----------

        void symbol(String id, String kind, String name, String qualified, String metadata, String state, String path, ASTNode at) {
            int line = Math.max(1, at.getLineNumber());
            int column = Math.max(1, at.getColumnNumber());
            int endLine = at.getLastLineNumber() > 0 ? at.getLastLineNumber() : line;
            int endColumn = at.getLastColumnNumber() > 0 ? at.getLastColumnNumber() : column;
            symbols.putIfAbsent(id, new Symbol(id, kind, name, qualified, metadata, state, path, line, column,
                    Math.max(line, endLine), Math.max(column, endColumn)));
        }

        void edge(String source, String target, String description, String type, String state, String path, ASTNode at, String evidence) {
            edges.add(new Relationship(source, target, description, type, target == null ? "UNRESOLVED" : state,
                    path, Math.max(1, at.getLineNumber()), Math.max(1, at.getColumnNumber()), evidence));
        }

        String context(String path, ClassNode at) {
            String id = "groovy:context:" + path;
            symbol(id, "CONTEXT", fileName(path), path, null, "RESOLVED", path, at);
            return id;
        }

        void error(String path, String code, String message) {
            errors.add(AnalysisError.of(path, "GROOVY", code, message));
        }

        static String qualifiedName(ClassNode node) {
            if (node.getOuterClass() != null) return qualifiedName(node.getOuterClass()) + "." + node.getName();
            String name = node.getName();
            String pkg = node.getPackageName();
            if (pkg == null || pkg.isBlank() || name.contains(".")) return name;
            return pkg.replaceAll("\\.$", "") + "." + name;
        }

        static String parameters(MethodNode method) {
            return Arrays.stream(method.getParameters()).map(p -> p.getType().getName()).reduce((a, b) -> a + "," + b).orElse("");
        }

        static String signature(MethodNode method) {
            return "(" + parameters(method) + ")";
        }

        static FieldNode staticField(ClassNode node, String name) {
            for (FieldNode field : node.getFields()) if (field.isStatic() && field.getName().equals(name)) return field;
            return null;
        }

        static boolean hasEntityAnnotation(ClassNode node) {
            return node.getAnnotations().stream().anyMatch(a -> {
                String name = a.getClassNode().getName();
                return name.equals("Entity") || name.equals("grails.gorm.annotation.Entity");
            });
        }

        ClosureExpression findBeans(ClassNode node) {
            for (MethodNode method : node.getMethods()) {
                if (!method.getName().equals("run") || method.getCode() == null) continue;
                ClosureExpression found = findBeans(method.getCode());
                if (found != null) return found;
            }
            return null;
        }

        static ClosureExpression findBeans(Statement statement) {
            if (statement instanceof BlockStatement block) {
                for (Statement child : block.getStatements()) {
                    ClosureExpression found = findBeans(child);
                    if (found != null) return found;
                }
                return null;
            }
            if (!(statement instanceof ExpressionStatement es)) return null;
            Expression expression = es.getExpression();
            Expression left = null, right = null;
            if (expression instanceof BinaryExpression binary) { left = binary.getLeftExpression(); right = binary.getRightExpression(); }
            if (expression instanceof DeclarationExpression declaration) { left = declaration.getLeftExpression(); right = declaration.getRightExpression(); }
            if (left instanceof VariableExpression variable && variable.getName().equals("beans") && right instanceof ClosureExpression closure) return closure;
            return null;
        }

        static List<Statement> statements(Statement statement) {
            return statement instanceof BlockStatement block ? block.getStatements() : List.of(statement);
        }

        static List<Expression> arguments(MethodCallExpression call) {
            return call.getArguments() instanceof TupleExpression tuple ? tuple.getExpressions() : List.of(call.getArguments());
        }

        static Map<String, Expression> namedArguments(MethodCallExpression call) {
            Map<String, Expression> named = new LinkedHashMap<>();
            List<Expression> arguments = arguments(call);
            if (arguments.size() == 1 && arguments.getFirst() instanceof MapExpression map) {
                for (MapEntryExpression entry : map.getMapEntryExpressions()) named.put(entry.getKeyExpression().getText(), entry.getValueExpression());
            }
            return named;
        }

        static String stringValue(Expression expression) {
            return expression instanceof ConstantExpression constant && constant.getValue() instanceof String value ? value : null;
        }

        /** Declared mapping text for literal strings and GString patterns such as "/customer/$id". */
        static String routeText(Expression method) {
            if (method instanceof ConstantExpression constant && constant.getValue() instanceof String value) return value;
            if (method instanceof GStringExpression gstring) {
                String text = gstring.getText();
                if (text.length() >= 2 && (text.startsWith("\"") || text.startsWith("'")) && text.charAt(text.length() - 1) == text.charAt(0)) {
                    return text.substring(1, text.length() - 1);
                }
                return text;
            }
            return null;
        }

        static String capitalize(String value) {
            return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
        }

        static String fileName(String path) {
            return Path.of(path).getFileName().toString();
        }

        static String candidateDescription(String label, Collection<String> candidates) {
            var sorted = candidates.stream().sorted().toList();
            return label + "; candidates=" + sorted.stream().limit(20).toList() + "; count=" + sorted.size();
        }
    }
}
