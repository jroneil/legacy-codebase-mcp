package com.oneil.legacy.framework;

import com.oneil.legacy.scan.RepositoryInventory;
import com.oneil.legacy.scan.ScanModel.AnalysisError;
import com.oneil.legacy.scan.ScanModel.Inventory;
import com.oneil.legacy.scan.ScanModel.SourceFile;
import com.oneil.legacy.symbol.JavaIndexModel.*;
import java.nio.file.Path;
import java.util.*;
import org.springframework.stereotype.Component;

/** Static XML evidence only. Never loads application classes or instantiates a Spring context. */
@Component
public class FrameworkIndexer {
    /** Request-parameter DispatchAction variants: org.apache.struts.actions and the Struts 1.3 extras location. */
    private static final Set<String> DISPATCH_TYPES = Set.of("org.apache.struts.actions.DispatchAction",
            "org.apache.struts.extras.actions.DispatchAction");

    public Index index(Path root, Inventory inventory, Index javaIndex) {
        var mvc = new SpringMvcIndexer().index(root, inventory, javaIndex);
        var xml = new Session(root, inventory, mvc).run();
        return new SpringAnnotationIndexer().index(root, inventory, xml);
    }

    private static final class Session {
        final Path root;
        final Map<String, SourceFile> files = new TreeMap<>();
        final Map<String, Symbol> symbols = new TreeMap<>();
        final List<Relationship> edges = new ArrayList<>();
        final List<AnalysisError> errors = new ArrayList<>();
        final Map<String, SafeXml.Element> documents = new TreeMap<>();
        final Set<String> attempted = new HashSet<>();
        final Set<String> springRoots = new TreeSet<>(), strutsFiles = new TreeSet<>();
        final Map<String, String> webRoots = new HashMap<>();
        final List<Bean> beans = new ArrayList<>();
        final List<Route> routes = new ArrayList<>();
        final List<WebServlet> servlets = new ArrayList<>();
        final Map<String, List<Bean>> beanNames = new TreeMap<>();
        record Bean(String id, String name, String context, String path, String className, SafeXml.Element xml) {}
        record Route(String id, String path, String actionPath, SafeXml.Element xml) {}
        record WebServlet(String id, String path, SafeXml.Element xml, Set<String> configs) {}

        Session(Path root, Inventory inventory, Index javaIndex) {
            this.root = root;
            inventory.files().forEach(f -> files.put(f.relativePath(), f));
            javaIndex.symbols().forEach(s -> {
                if (symbols.put(s.stableId(), s) != null) throw new IllegalArgumentException("Duplicate symbol identity");
            });
            edges.addAll(javaIndex.relationships());
            errors.addAll(javaIndex.errors());
        }
        Index run() {
            for (String path : files.keySet()) {
                String name = Path.of(path).getFileName().toString();
                if (name.equals("web.xml")) web(path);
                if (name.matches("struts-config(?:[.-].*)?\\.xml")) strutsFiles.add(path);
                if (name.matches("applicationContext(?:[.-].*)?\\.xml")) springRoots.add(path);
            }
            // Imported conventional filenames belong to the importing context, not a second context.
            Set<String> imported = new HashSet<>();
            for (String path : new TreeSet<>(springRoots)) discoverImports(path, imported, new HashSet<>());
            Set<String> roots = new TreeSet<>(springRoots);
            roots.removeAll(imported);
            if (roots.isEmpty() && !springRoots.isEmpty()) roots.add(springRoots.iterator().next());
            for (String context : roots) spring(context, context, new HashSet<>(), new HashSet<>());
            // A cycle may have no unimported root. Still analyze each otherwise unreachable component.
            for (String context : springRoots) if (!symbols.containsKey("xml:context:" + context))
                spring(context, context, new HashSet<>(), new HashSet<>());
            for (Bean bean : beans) wire(bean);
            legacyMvc();
            for (String path : strutsFiles) struts(path);
            for (Route route : routes) forwards(route);
            for (WebServlet servlet : servlets) for (Route route : routes) {
                if (servlet.configs.contains(route.path)) edge(servlet.id, route.id, route.actionPath,
                        "ROUTES_TO", "RESOLVED", servlet.path, servlet.xml);
            }
            return new Index(new ArrayList<>(symbols.values()), edges.stream().distinct()
                    .sorted(Comparator.comparing(Relationship::sourceId).thenComparing(Relationship::type)
                            .thenComparing(Relationship::sourcePath).thenComparingInt(Relationship::line)
                            .thenComparing(e -> Objects.toString(e.targetId(), e.targetDescription())))
                    .toList(), errors.stream().distinct().toList());
        }
        SafeXml.Element xml(String path, String expected) {
            if (!attempted.add(path)) return documents.get(path);
            try {
                var file = files.get(path);
                if (file == null) { error(path, "XML_REFERENCE_MISSING"); return null; }
                var doc = SafeXml.parse(new RepositoryInventory().readVerified(root, file));
                if (doc == null || !doc.name.equals(expected)) { error(path, "XML_ROOT_UNSUPPORTED"); return null; }
                documents.put(path, doc);
                return doc;
            } catch (Exception invalid) { error(path, "XML_PARSE"); return null; }
        }
        void error(String path, String code) {
            errors.add(AnalysisError.of(path, "XML", code, "Configuration evidence could not be fully resolved (" + code + ")."));
        }
        String context(String path, SafeXml.Element xml) {
            String id = "xml:context:" + path;
            symbol(id, "CONTEXT", Path.of(path).getFileName().toString(), path, null, path, xml);
            return id;
        }
        void symbol(String id, String kind, String name, String qualified, String signature, String path, SafeXml.Element at) {
            symbols.putIfAbsent(id, new Symbol(id, kind, name, qualified, signature, "RESOLVED", path,
                    at.line, at.column, at.line, at.column));
        }
        void edge(String source, String target, String description, String type, String state, String path, SafeXml.Element at) {
            edges.add(new Relationship(source, target, description, type, target == null ? "UNRESOLVED" : state,
                    path, at.line, at.column, "XML"));
        }
        String type(String className) { return symbols.containsKey("java:type:" + className) ? "java:type:" + className : null; }
        String candidateDescription(String label, Collection<String> candidates) {
            var sorted = candidates.stream().sorted().toList();
            return label + "; candidates=" + sorted.stream().limit(20).toList() + "; count=" + sorted.size();
        }
        String reference(String from, String value, boolean webRelative) {
            if (value.isBlank() || value.contains("*") || value.contains("${") || value.contains("\\")) {
                error(from, "XML_REFERENCE_UNSUPPORTED"); return null;
            }
            String result;
            if (value.startsWith("classpath:")) {
                String suffix = value.substring(10).replaceFirst("^/", "");
                var matches = files.keySet().stream().filter(p -> p.equals(suffix) || p.endsWith("/" + suffix)).toList();
                if (matches.size() != 1) { error(from, "XML_REFERENCE_AMBIGUOUS_OR_MISSING"); return null; }
                result = matches.getFirst();
            } else {
                if (value.contains(":")) { error(from, "XML_REFERENCE_UNSUPPORTED"); return null; }
                Path base = Path.of(from).getParent();
                if (base == null) base = Path.of("");
                if (webRelative || value.startsWith("/")) base = Path.of(webRoot(from));
                result = base.resolve(value.replaceFirst("^/", "")).normalize().toString().replace('\\', '/');
            }
            if (Path.of(result).startsWith("..")) { error(from, "XML_REFERENCE_UNSUPPORTED"); return null; }
            if (!files.containsKey(result)) { error(from, "XML_REFERENCE_MISSING"); return null; }
            return result;
        }
        String webRoot(String path) {
            if (webRoots.containsKey(path)) return webRoots.get(path);
            int i = path.indexOf("WEB-INF/");
            return i >= 0 ? path.substring(0, i) : Objects.toString(Path.of(path).getParent(), "");
        }
        void web(String path) {
            var doc = xml(path, "web-app");
            if (doc == null) return;
            String ctx = context(path, doc);
            for (var param : doc.children("context-param")) if (param.childText("param-name").equals("contextConfigLocation")) {
                for (String value : param.childText("param-value").split("[,;\\s]+")) {
                    String ref = reference(path, value, true);
                    if (ref != null) { springRoots.add(ref); webRoots.put(ref, webRoot(path)); }
                }
            }
            for (var servlet : doc.children("servlet")) {
                String name = servlet.childText("servlet-name"), cls = servlet.childText("servlet-class");
                String id = "web:servlet:" + path + "#" + name;
                symbol(id, "SERVLET", name, name, cls, path, servlet);
                edge(ctx, id, null, "CONTAINS", "RESOLVED", path, servlet);
                if (!cls.equals("org.apache.struts.action.ActionServlet")) continue;
                Set<String> configs = new TreeSet<>();
                for (var param : servlet.children("init-param")) if (param.childText("param-name").equals("config") || param.childText("param-name").startsWith("config/")) {
                    for (String value : param.childText("param-value").split("[,\\s]+")) {
                        String ref = reference(path, value, true);
                        if (ref != null) { configs.add(ref); strutsFiles.add(ref); webRoots.put(ref, webRoot(path)); }
                    }
                }
                if (configs.isEmpty()) {
                    String ref = reference(path, "/WEB-INF/struts-config.xml", true);
                    if (ref != null) configs.add(ref);
                }
                servlets.add(new WebServlet(id, path, servlet, configs));
                for (var mapping : doc.children("servlet-mapping")) if (mapping.childText("servlet-name").equals(name)) {
                    // Preserve the servlet's URL pattern as evidence without interpreting it as an action path.
                    edge(ctx, id, "url-pattern=" + mapping.childText("url-pattern"), "ROUTES_TO", "RESOLVED", path, mapping);
                }
            }
        }
        void discoverImports(String path, Set<String> imported, Set<String> visited) {
            if (visited.size() >= 100) { error(path, "SPRING_IMPORT_LIMIT"); return; }
            if (!visited.add(path)) return;
            var doc = xml(path, "beans");
            if (doc == null) return;
            for (var imp : doc.children("import")) {
                String ref = reference(path, imp.attr("resource"), false);
                if (ref != null) { imported.add(ref); discoverImports(ref, imported, visited); }
            }
        }
        void spring(String context, String path, Set<String> visited, Set<String> stack) {
            if (stack.contains(path)) { error(path, "SPRING_IMPORT_CYCLE"); return; }
            if (visited.size() >= 100) { error(path, "SPRING_IMPORT_LIMIT"); return; }
            if (!visited.add(path)) return;
            var doc = xml(path, "beans");
            if (doc == null) return;
            String ctx = context(path, doc);
            stack.add(path);
            for (var imp : doc.children("import")) {
                String ref = reference(path, imp.attr("resource"), false);
                if (ref != null) {
                    spring(context, ref, visited, stack);
                    if (symbols.containsKey("xml:context:" + ref)) edge(ctx, "xml:context:" + ref, null, "IMPORTS", "RESOLVED", path, imp);
                }
            }
            stack.remove(path);
            int ordinal = 0;
            for (var node : doc.children("bean")) {
                String name = node.attr("id");
                List<String> aliases = new ArrayList<>(Arrays.stream(node.attr("name").split("[,;\\s]+")).filter(s -> !s.isBlank()).toList());
                if (name.isBlank()) name = aliases.isEmpty() ? "anonymous-" + (++ordinal) : aliases.getFirst();
                String id = "spring:bean:" + context + "#" + name;
                if (symbols.containsKey(id)) id += "@" + path + ":" + node.line + ":" + node.column;
                String cls = node.attr("class");
                Bean bean = new Bean(id, name, context, path, cls, node);
                beans.add(bean);
                aliases.add(name);
                for (String alias : new LinkedHashSet<>(aliases)) beanNames.computeIfAbsent(context + "#" + alias, k -> new ArrayList<>()).add(bean);
                symbol(id, "BEAN", name, context + "#" + name, cls, path, node);
                edge(ctx, id, null, "CONTAINS", "RESOLVED", path, node);
                String target = validClass(cls) && ordinaryBean(node) ? type(cls) : null;
                edge(id, target, validClass(cls) ? cls : "Bean class unavailable", "WIRES_TO", "RESOLVED", path, node);
                if (target == null) error(path, "SPRING_BEAN_CLASS_UNRESOLVED");
            }
        }
        void legacyMvc() {
            String mappingType = "org.springframework.web.servlet.handler.BeanNameUrlHandlerMapping";
            var contexts = beans.stream().filter(b -> b.className.equals(mappingType) && ordinaryBean(b.xml))
                    .map(Bean::context).collect(java.util.stream.Collectors.toCollection(TreeSet::new));
            for (Bean bean : beans) {
                if (!contexts.contains(bean.context) || !bean.name.startsWith("/") || !ordinaryBean(bean.xml)) continue;
                var methods = symbols.values().stream().filter(s -> s.kind().equals("METHOD")
                        && s.stableId().startsWith("java:method:" + bean.className + "#")
                        && s.simpleName().equals("handleRequest")).toList();
                String id = "spring-mvc:route:" + bean.path + "#UNSPECIFIED:" + bean.name
                        + "#" + bean.id + "@" + bean.xml.line + ":" + bean.xml.column;
                symbol(id, "ROUTE", bean.name, bean.name,
                        "httpMethod=UNSPECIFIED; mapping=BeanNameUrlHandlerMapping", bean.path, bean.xml);
                edge("xml:context:" + bean.path, id, null, "CONTAINS", "RESOLVED", bean.path, bean.xml);
                String target = methods.size() == 1 ? methods.getFirst().stableId() : null;
                edge(id, target, candidateDescription("BeanNameUrlHandlerMapping handleRequest convention",
                        methods.stream().map(Symbol::stableId).toList()), "ROUTES_TO",
                        methods.size() == 1 ? "INFERRED" : "UNRESOLVED", bean.path, bean.xml);
                if (target == null) error(bean.path, "SPRING_MVC_XML_HANDLER_UNRESOLVED");
            }
        }
        boolean ordinaryBean(SafeXml.Element node) {
            return node.attr("factory-method").isBlank() && node.attr("factory-bean").isBlank() && !node.attr("abstract").equals("true");
        }
        boolean validClass(String name) { return name.matches("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*"); }
        void wire(Bean bean) {
            Set<String> explicit = new HashSet<>();
            for (var child : bean.xml.children) {
                if (!Set.of("property", "constructor-arg").contains(child.name)) continue;
                if (child.name.equals("property")) explicit.add(child.attr("name"));
                String ref = child.attr("ref");
                if (ref.isBlank() && !child.children("ref").isEmpty()) {
                    var reference = child.children("ref").getFirst();
                    ref = reference.attr("bean");
                    if (ref.isBlank()) ref = reference.attr("local");
                }
                if (ref.isBlank()) {
                    if (!child.children("bean").isEmpty() || !child.descendants("ref").isEmpty()) {
                        edge(bean.id, null, "Unsupported nested injection", "INJECTS", "UNRESOLVED", bean.path, child);
                        error(bean.path, "SPRING_INJECTION_UNSUPPORTED");
                    }
                    continue;
                }
                var candidates = beanNames.getOrDefault(bean.context + "#" + ref, List.of());
                inject(bean, candidates, child, "bean-ref=" + ref, "RESOLVED", propertyType(bean, child));
            }
            if (bean.xml.attr("autowire").equals("byType")) {
                for (var method : new ArrayList<>(symbols.values())) {
                    if (!method.kind().equals("METHOD") || !method.stableId().startsWith("java:method:" + bean.className + "#set") || method.signature() == null) continue;
                    String name = method.simpleName();
                    if (name.length() <= 3) continue;
                    String property = Character.toLowerCase(name.charAt(3)) + name.substring(4);
                    if (explicit.contains(property)) continue;
                    String requested = parameterType(method.signature());
                    if (requested == null) continue;
                    var candidates = beans.stream().filter(b -> b.context.equals(bean.context) && ordinaryBean(b.xml) && !b.xml.attr("autowire-candidate").equals("false")
                            && implementsType(b.className, requested, new HashSet<>())).toList();
                    inject(bean, candidates, bean.xml, "autowire-byType=" + requested, "INFERRED", requested);
                }
            } else if (!bean.xml.attr("autowire").isBlank() && !bean.xml.attr("autowire").equals("no")) {
                edge(bean.id, null, "Unsupported autowire mode", "INJECTS", "UNRESOLVED", bean.path, bean.xml);
            }
        }
        String propertyType(Bean bean, SafeXml.Element child) {
            if (child.name.equals("constructor-arg")) return child.attr("type").isBlank() ? null : child.attr("type");
            String name = child.attr("name");
            if (name.isBlank()) return null;
            String setter = "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
            var types = symbols.values().stream().filter(s -> s.kind().equals("METHOD") && s.stableId().startsWith("java:method:" + bean.className + "#")
                    && s.simpleName().equals(setter)).map(s -> parameterType(s.signature())).filter(Objects::nonNull).distinct().toList();
            return types.size() == 1 ? types.getFirst() : null;
        }
        String parameterType(String signature) {
            if (signature == null || !signature.contains("(")) return null;
            String parameters = signature.substring(signature.indexOf('(') + 1, signature.lastIndexOf(')'));
            return parameters.isBlank() || parameters.contains(",") || parameters.contains("?") ? null : parameters;
        }
        boolean implementsType(String cls, String requested, Set<String> visited) {
            if (cls.equals(requested)) return true;
            if (!visited.add(cls)) return false;
            for (var edge : edges) if (edge.sourceId().equals("java:type:" + cls) && Set.of("IMPLEMENTS", "EXTENDS").contains(edge.type())
                    && edge.targetId() != null && edge.resolutionState().equals("RESOLVED")) {
                if (implementsType(edge.targetId().substring("java:type:".length()), requested, visited)) return true;
            }
            return false;
        }
        void inject(Bean bean, List<Bean> candidates, SafeXml.Element at, String description, String state, String requested) {
            if (candidates.size() != 1) {
                edge(bean.id, null, candidateDescription(description, candidates.stream().map(Bean::id).toList()), "INJECTS", "UNRESOLVED", bean.path, at);
                return;
            }
            Bean target = candidates.getFirst();
            edge(bean.id, target.id, description, "INJECTS", state, bean.path, at);
            if (requested != null && type(requested) != null && type(target.className) != null && ordinaryBean(target.xml) && implementsType(target.className, requested, new HashSet<>())) {
                edge(type(requested), type(target.className), "binding=" + bean.id + "; " + description, "WIRES_TO", state, bean.path, at);
            }
        }
        void struts(String path) {
            var doc = xml(path, "struts-config");
            if (doc == null) return;
            String ctx = context(path, doc);
            for (var form : doc.descendants("form-bean")) {
                String id = "struts:form:" + path + "#" + form.attr("name");
                if (symbols.containsKey(id)) id += "@" + form.line + ":" + form.column;
                symbol(id, "FORM", form.attr("name"), form.attr("name"), form.attr("type"), path, form);
                edge(ctx, id, null, "CONTAINS", "RESOLVED", path, form);
                edge(id, type(form.attr("type")), form.attr("type"), "WIRES_TO", "RESOLVED", path, form);
            }
            for (var action : doc.descendants("action")) {
                String id = "struts:route:" + path + "#" + action.attr("path");
                if (symbols.containsKey(id)) id += "@" + action.line + ":" + action.column;
                symbol(id, "ROUTE", action.attr("path"), action.attr("path"), action.attr("parameter"), path, action);
                edge(ctx, id, null, "CONTAINS", "RESOLVED", path, action);
                routes.add(new Route(id, path, action.attr("path"), action));
                String cls = action.attr("type"), target = type(cls);
                if (!cls.isBlank()) edge(id, target, cls, "ROUTES_TO", "RESOLVED", path, action);
                if (!action.attr("name").isBlank()) {
                    var forms = symbols.values().stream().filter(s -> s.kind().equals("FORM") && s.sourcePath().equals(path) && s.simpleName().equals(action.attr("name"))).toList();
                    edge(id, forms.size() == 1 ? forms.getFirst().stableId() : null, candidateDescription("form=" + action.attr("name"), forms.stream().map(Symbol::stableId).toList()), "WIRES_TO", "RESOLVED", path, action);
                }
                if (target != null) {
                    var matches = beans.stream().filter(b -> b.className.equals(cls) && ordinaryBean(b.xml)).toList();
                    if (!matches.isEmpty()) edge(target, matches.size() == 1 ? matches.getFirst().id : null,
                            candidateDescription("Action class matches Spring bean; runtime delegation unverified", matches.stream().map(Bean::id).toList()), "WIRES_TO", "INFERRED", path, action);
                    if (!action.attr("parameter").isBlank() && dispatch(cls, new HashSet<>())) {
                        for (var method : new ArrayList<>(symbols.values())) if (method.kind().equals("METHOD") && method.stableId().startsWith("java:method:" + cls + "#") && dispatchSignature(method.signature())) {
                            edge(id, method.stableId(), "request parameter " + action.attr("parameter") + "=" + method.simpleName(), "ROUTES_TO", "INFERRED", path, action);
                        }
                    }
                }
            }
        }
        boolean dispatchSignature(String signature) {
            if (signature == null || !signature.contains("(")) return false;
            String[] args = signature.substring(signature.indexOf('(') + 1, signature.lastIndexOf(')')).split(",");
            String[] expected = {"ActionMapping", "ActionForm", "HttpServletRequest", "HttpServletResponse"};
            if (args.length != 4) return false;
            for (int i = 0; i < 4; i++) if (!args[i].replace("?", "").matches("(?:[\\w$.]+\\.)?" + expected[i])) return false;
            return true;
        }
        boolean dispatch(String cls, Set<String> visited) {
            if (!visited.add(cls)) return false;
            for (var edge : edges) if (edge.sourceId().equals("java:type:" + cls) && edge.type().equals("EXTENDS")) {
                String parent = edge.targetId() == null ? edge.targetDescription() : edge.targetId().replaceFirst("^java:type:", "");
                if (DISPATCH_TYPES.contains(parent)) return true;
                if ("DispatchAction".equals(parent) && edges.stream().anyMatch(e -> e.type().equals("IMPORTS") && e.sourcePath().equals(edge.sourcePath()) && DISPATCH_TYPES.contains(e.targetDescription()))) return true;
                // Mapping/lookup dispatch choose the method from configuration or a resource key, not a request parameter.
                if (parent != null && (parent.endsWith("MappingDispatchAction") || parent.endsWith("LookupDispatchAction"))) continue;
                if (parent != null && dispatch(parent, visited)) return true;
            }
            return false;
        }
        void forwards(Route route) {
            var doc = documents.get(route.path);
            List<SafeXml.Element> forwards = new ArrayList<>();
            for (var global : doc.children("global-forwards")) forwards.addAll(global.children("forward"));
            // A local name overrides a global name according to Struts configuration semantics.
            Set<String> localNames = new HashSet<>();
            route.xml.children("forward").forEach(f -> localNames.add(f.attr("name")));
            forwards.removeIf(f -> localNames.contains(f.attr("name")));
            forwards.addAll(route.xml.children("forward"));
            for (var forward : forwards) {
                String id = route.id + "/forward:" + forward.attr("name") + "@" + forward.line + ":" + forward.column;
                symbol(id, "FORWARD", forward.attr("name"), route.actionPath + "#" + forward.attr("name"), null, route.path, forward);
                edge(route.id, id, null, "CONTAINS", "RESOLVED", route.path, forward);
                destination(id, route.path, forward.attr("path"), forward);
            }
            if (!route.xml.attr("forward").isBlank()) destination(route.id, route.path, route.xml.attr("forward"), route.xml);
            if (!route.xml.attr("input").isBlank()) destination(route.id, route.path, route.xml.attr("input"), route.xml);
        }
        void destination(String source, String path, String raw, SafeXml.Element at) {
            String value = raw.split("[?#]", 2)[0];
            if (value.contains(":") || value.contains("${") || value.startsWith("//")) {
                edge(source, null, "Unsupported external or dynamic forward", "FORWARDS_TO", "UNRESOLVED", path, at);
                return;
            }
            if (value.endsWith(".jsp") || value.endsWith(".html")) {
                String ref = reference(path, value, true);
                String id = null;
                if (ref != null) {
                    id = "web:view:" + ref;
                    symbols.putIfAbsent(id, new Symbol(id, "VIEW", Path.of(ref).getFileName().toString(), ref, null, "RESOLVED", ref, 1, 1, 1, 1));
                }
                edge(source, id, value, "RENDERS", "RESOLVED", path, at);
            } else {
                String actionPath = value.replaceFirst("\\.do$", "");
                var candidates = routes.stream().filter(r -> r.actionPath.equals(actionPath) && webRoot(r.path).equals(webRoot(path))).toList();
                edge(source, candidates.size() == 1 ? candidates.getFirst().id : null,
                        candidateDescription(actionPath, candidates.stream().map(Route::id).toList()), "FORWARDS_TO", "RESOLVED", path, at);
            }
        }
    }
}
