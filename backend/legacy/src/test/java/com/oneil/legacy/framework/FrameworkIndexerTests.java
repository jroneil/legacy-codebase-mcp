package com.oneil.legacy.framework;

import static org.assertj.core.api.Assertions.*;
import com.oneil.legacy.scan.RepositoryInventory;
import com.oneil.legacy.symbol.JavaIndexModel.*;
import com.oneil.legacy.symbol.JavaSymbolIndexer;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class FrameworkIndexerTests {
    @TempDir Path root;
    static final String CONFIG = "web/WEB-INF/struts-config.xml";
    static final String CONTEXT = "web/WEB-INF/applicationContext.xml";
    static final String ROUTE = "struts:route:" + CONFIG + "#/customer/search";
    static final String ACTION = "spring:bean:" + CONTEXT + "#customerAction";
    static final String SERVICE = "spring:bean:" + CONTEXT + "#customerService";
    static final String DAO = "spring:bean:" + CONTEXT + "#customerDao";
    @BeforeEach void fixture() throws Exception { copyFixture(root); }
    static void copyFixture(Path root) throws Exception {
        Path source = Path.of(FrameworkIndexerTests.class.getResource("/fixtures/struts-spring").toURI());
        try (var files = Files.walk(source)) {
            for (var file : files.toList()) {
                var target = root.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file)) Files.createDirectories(target); else Files.copy(file, target);
            }
        }
    }
    Index index() throws Exception {
        var inventory = new RepositoryInventory().collect(root);
        return new FrameworkIndexer().index(root, inventory, new JavaSymbolIndexer().index(root, inventory));
    }
    @Test void routesFormsForwardsServletsAndLocations() throws Exception {
        var index = index();
        assertThat(index.symbols()).extracting(Symbol::kind).contains("ROUTE", "FORM", "FORWARD", "VIEW", "SERVLET", "BEAN");
        assertEdge(index, ROUTE, "java:type:demo.CustomerAction", "ROUTES_TO", "RESOLVED");
        assertEdge(index, ROUTE, "struts:form:" + CONFIG + "#customerForm", "WIRES_TO", "RESOLVED");
        assertThat(index.relationships()).anyMatch(e -> e.type().equals("RENDERS") && "web:view:web/WEB-INF/views/search.jsp".equals(e.targetId()));
        assertThat(index.relationships()).anyMatch(e -> e.type().equals("FORWARDS_TO") && ("struts:route:" + CONFIG + "#/customer/edit").equals(e.targetId()));
        assertThat(index.relationships()).anyMatch(e -> e.sourceId().startsWith("web:servlet:") && ROUTE.equals(e.targetId()));
        assertThat(index.relationships()).filteredOn(e -> e.evidenceType().equals("XML")).allSatisfy(e -> {
            assertThat(e.line()).isPositive(); assertThat(e.column()).isPositive(); assertThat(e.sourcePath()).endsWith(".xml");
        });
    }
    @Test void explicitInjectionAndInterfaceBindingsUseImportedBeans() throws Exception {
        var index = index();
        assertEdge(index, ACTION, SERVICE, "INJECTS", "RESOLVED");
        assertEdge(index, SERVICE, DAO, "INJECTS", "RESOLVED");
        assertEdge(index, "java:type:demo.CustomerService", "java:type:demo.CustomerServiceImpl", "WIRES_TO", "RESOLVED");
        assertEdge(index, "java:type:demo.CustomerDAO", "java:type:demo.CustomerDAOImpl", "WIRES_TO", "RESOLVED");
        assertEdge(index, "java:type:demo.CustomerAction", ACTION, "WIRES_TO", "INFERRED");
        assertEdge(index, "xml:context:" + CONTEXT, "xml:context:web/WEB-INF/wiring.xml", "IMPORTS", "RESOLVED");
        assertThat(index.toString()).doesNotContain("fixture-secret-must-not-escape");
    }
    @Test void dispatchParameterIsARequestKeyAndMethodsRemainConditional() throws Exception {
        var index = index();
        var edges = index.relationships().stream().filter(e -> e.sourceId().equals("struts:route:" + CONFIG + "#/customer/edit")
                && e.targetId() != null && e.targetId().startsWith("java:method:")).toList();
        assertThat(edges).hasSize(2).allSatisfy(e -> {
            assertThat(e.resolutionState()).isEqualTo("INFERRED");
            assertThat(e.targetDescription()).startsWith("request parameter operation=");
        });
        assertThat(edges).noneMatch(e -> e.targetId().contains("#operation(") || e.targetId().contains("#helper("));
    }
    @Test void ambiguityAndMissingClassAreNotSilentlyResolved() throws Exception {
        var index = index();
        assertThat(index.relationships()).filteredOn(e -> e.sourceId().endsWith("#ambiguousService") && e.type().equals("INJECTS"))
                .singleElement().satisfies(e -> {
                    assertThat(e.targetId()).isNull(); assertThat(e.resolutionState()).isEqualTo("UNRESOLVED");
                    assertThat(e.targetDescription()).contains("#customerDao", "#alternativeDao", "count=2");
                });
        assertThat(index.relationships()).filteredOn(e -> e.sourceId().endsWith("#missingClass") && e.type().equals("WIRES_TO"))
                .singleElement().satisfies(e -> assertThat(e.resolutionState()).isEqualTo("UNRESOLVED"));
        assertThat(index.errors()).anyMatch(e -> e.code().equals("XML_PARSE") && e.relativePath().equals("applicationContext-broken.xml"))
                .anyMatch(e -> e.code().equals("SPRING_BEAN_CLASS_UNRESOLVED"));
    }
    @Test void repeatedAnalysisIsDeterministicAndInputRemainsUnmodified() throws Exception {
        var before = new RepositoryInventory().collect(root);
        var first = index();
        assertThat(index()).isEqualTo(first);
        assertThat(new RepositoryInventory().collect(root).files()).isEqualTo(before.files());
    }
    @Test void duplicateBeanNamesPreserveCandidates() throws Exception {
        Files.writeString(root.resolve("web/WEB-INF/wiring.xml"), "<beans><bean id='customerDao' class='demo.CustomerDAOImpl'/><bean id='customerDao' class='demo.AlternativeDAO'/></beans>");
        assertThat(index().relationships()).filteredOn(e -> e.sourceId().equals(SERVICE) && e.type().equals("INJECTS"))
                .singleElement().satisfies(e -> {
                    assertThat(e.targetId()).isNull(); assertThat(e.targetDescription()).contains("count=2");
                });
    }
    @Test void independentContextsDoNotResolveEachOthersReferences() throws Exception {
        Files.writeString(root.resolve("applicationContext-other.xml"), "<beans><bean id='customerDao' class='demo.AlternativeDAO'/><bean id='other' class='demo.CustomerAction'><property name='service' ref='customerService'/></bean></beans>");
        var index = index();
        assertEdge(index, SERVICE, DAO, "INJECTS", "RESOLVED");
        assertThat(index.relationships()).filteredOn(e -> e.sourceId().endsWith("#other") && e.type().equals("INJECTS"))
                .singleElement().satisfies(e -> assertThat(e.targetId()).isNull());
        assertThat(index.relationships()).filteredOn(e -> e.sourceId().equals("java:type:demo.CustomerAction") && e.type().equals("WIRES_TO"))
                .allSatisfy(e -> assertThat(e.resolutionState()).isEqualTo("UNRESOLVED"));
    }
    @Test void importCyclesAreLocalizedAndDoNotDuplicateBeans() throws Exception {
        Files.writeString(root.resolve("web/WEB-INF/wiring.xml"), "<beans><import resource='applicationContext.xml'/><bean id='customerDao' class='demo.CustomerDAOImpl'/></beans>");
        var index = index();
        assertThat(index.errors()).anyMatch(e -> e.code().equals("SPRING_IMPORT_CYCLE"));
        assertThat(index.symbols()).filteredOn(s -> s.stableId().equals(DAO)).hasSize(1);
    }
    @Test void externalEntitiesAndOutOfInventoryImportsCannotReadFiles() throws Exception {
        Path secret = Files.createTempFile("slice3-secret-", ".txt");
        try {
            Files.writeString(secret, "fixture-secret-must-not-escape");
            Files.writeString(root.resolve("applicationContext-xxe.xml"), "<!DOCTYPE beans [<!ENTITY xxe SYSTEM '" + secret.toUri() + "'>]><beans>&xxe;</beans>");
            Files.writeString(root.resolve("applicationContext-network.xml"), "<beans><import resource='http://127.0.0.1:1/secret.xml'/><import resource='../../secret.xml'/></beans>");
            var index = index();
            assertThat(index.errors()).anyMatch(e -> e.relativePath().equals("applicationContext-xxe.xml") && e.code().equals("XML_PARSE"));
            assertThat(index.errors()).anyMatch(e -> e.code().equals("XML_REFERENCE_UNSUPPORTED"));
            assertThat(index.toString()).doesNotContain("fixture-secret-must-not-escape", secret.toString());
        } finally { Files.deleteIfExists(secret); }
    }
    @Test void xmlHashChangesAndSymlinkSubstitutionsAreRejected() throws Exception {
        var inventory = new RepositoryInventory().collect(root);
        Files.writeString(root.resolve(CONTEXT), "<beans/>");
        var changed = new FrameworkIndexer().index(root, inventory, Index.empty());
        assertThat(changed.errors()).anyMatch(e -> e.relativePath().equals(CONTEXT) && e.code().equals("XML_PARSE"));
        Files.delete(root.resolve(CONTEXT));
        Files.createSymbolicLink(root.resolve(CONTEXT), root.resolve("web/WEB-INF/wiring.xml"));
        assertThat(new FrameworkIndexer().index(root, inventory, Index.empty()).errors())
                .anyMatch(e -> e.relativePath().equals(CONTEXT) && e.code().equals("XML_PARSE"));
    }
    @Test void localForwardOverridesGlobalAndUnknownActionRemainsUnresolved() throws Exception {
        Files.writeString(root.resolve(CONFIG), "<struts-config><global-forwards><forward name='success' path='/missing.do'/></global-forwards><action-mappings><action path='/a' type='demo.CustomerAction'><forward name='success' path='/WEB-INF/views/home.jsp'/><forward name='unknown' path='/missing.do'/></action></action-mappings></struts-config>");
        var index = index();
        assertThat(index.symbols()).filteredOn(s -> s.kind().equals("FORWARD") && s.simpleName().equals("success")).hasSize(1);
        assertThat(index.relationships()).filteredOn(e -> e.type().equals("FORWARDS_TO"))
                .singleElement().satisfies(e -> assertThat(e.resolutionState()).isEqualTo("UNRESOLVED"));
    }
    @Test void aUniqueByTypeCandidateIsInferredAndExplicitMissingRefsStayUnresolved() throws Exception {
        Files.writeString(root.resolve("web/WEB-INF/wiring.xml"), "<beans><bean id='customerDao' class='demo.CustomerDAOImpl'/></beans>");
        var index = index();
        assertEdge(index, "spring:bean:" + CONTEXT + "#ambiguousService", DAO, "INJECTS", "INFERRED");
        Files.writeString(root.resolve("web/WEB-INF/wiring.xml"), "<beans/>");
        assertThat(index().relationships()).filteredOn(e -> e.sourceId().equals(SERVICE) && e.type().equals("INJECTS"))
                .singleElement().satisfies(e -> { assertThat(e.targetId()).isNull(); assertThat(e.targetDescription()).contains("count=0"); });
    }
    @Test void parentRelativeImportsStayInsideInventoryAndFactoryClassesAreNotRuntimeTargets() throws Exception {
        Files.writeString(root.resolve("web/WEB-INF/wiring.xml"), "<beans><import resource='../shared.xml'/><bean id='customerDao' class='demo.CustomerDAOImpl' factory-method='create'/></beans>");
        Files.writeString(root.resolve("web/shared.xml"), "<beans><bean id='shared' class='demo.AlternativeDAO'/></beans>");
        var index = index();
        assertThat(index.symbols()).anyMatch(s -> s.stableId().endsWith("#shared"));
        assertThat(index.relationships()).filteredOn(e -> e.sourceId().equals(DAO) && e.type().equals("WIRES_TO"))
                .singleElement().satisfies(e -> assertThat(e.targetId()).isNull());
        assertThat(index.relationships()).noneMatch(e -> e.sourceId().equals("java:type:demo.CustomerDAO") && "java:type:demo.CustomerDAOImpl".equals(e.targetId()) && e.type().equals("WIRES_TO"));
    }
    @Test void unsupportedNamespacesAndSecretBearingForwardUrlsAreNotExposedAsResolvedEvidence() throws Exception {
        Files.writeString(root.resolve("applicationContext-extension.xml"), "<beans xmlns:x='urn:unknown'><x:bean id='fake' class='demo.CustomerAction'/></beans>");
        Files.writeString(root.resolve(CONFIG), "<struts-config><action-mappings><action path='/a' type='demo.CustomerAction'><forward name='external' path='https://user:fixture-secret-must-not-escape@example.org/page.jsp'/></action></action-mappings></struts-config>");
        var index = index();
        assertThat(index.symbols()).noneMatch(s -> s.simpleName().equals("fake"));
        assertThat(index.toString()).doesNotContain("fixture-secret-must-not-escape");
        assertThat(index.relationships()).filteredOn(e -> e.type().equals("FORWARDS_TO"))
                .singleElement().satisfies(e -> assertThat(e.resolutionState()).isEqualTo("UNRESOLVED"));
    }
    @Test void extrasDispatchActionIsRecognizedButMappingAndLookupDispatchAreNot() throws Exception {
        Path other = root.resolve("extras-dispatch");
        Files.createDirectories(other.resolve("web/WEB-INF"));
        Files.writeString(other.resolve("web/WEB-INF/struts-config.xml"), """
                <struts-config><action-mappings>
                  <action path="/extras" type="demo.ExtrasDispatchAction" parameter="dispatchMethod"/>
                  <action path="/mapping" type="demo.MappingExampleAction" parameter="doFoo"/>
                  <action path="/lookup" type="demo.LookupExampleAction" parameter="dispatchMethod"/>
                </action-mappings></struts-config>
                """);
        Files.createDirectories(other.resolve("src/demo"));
        // Struts 1.3 moved DispatchAction to the extras module; unresolved parents keep their written name.
        Files.writeString(other.resolve("src/demo/ExtrasDispatchAction.java"), dispatchClass("ExtrasDispatchAction", "org.apache.struts.extras.actions.DispatchAction"));
        Files.writeString(other.resolve("src/demo/MappingExampleAction.java"), dispatchClass("MappingExampleAction", "org.apache.struts.extras.actions.MappingDispatchAction"));
        Files.writeString(other.resolve("src/demo/LookupExampleAction.java"), dispatchClass("LookupExampleAction", "org.apache.struts.extras.actions.LookupDispatchAction"));
        var inventory = new RepositoryInventory().collect(other);
        var index = new FrameworkIndexer().index(other, inventory, new JavaSymbolIndexer().index(other, inventory));
        String config = "web/WEB-INF/struts-config.xml";

        var extras = methodEdges(index, "struts:route:" + config + "#/extras");
        assertThat(extras).hasSize(2).allSatisfy(e -> {
            assertThat(e.resolutionState()).isEqualTo("INFERRED");
            assertThat(e.targetDescription()).startsWith("request parameter dispatchMethod=");
        });
        // Mapping/lookup dispatch select the method from configuration or a resource key, not a request parameter.
        assertThat(methodEdges(index, "struts:route:" + config + "#/mapping")).isEmpty();
        assertThat(methodEdges(index, "struts:route:" + config + "#/lookup")).isEmpty();
    }

    static String dispatchClass(String name, String parent) {
        String params = "org.apache.struts.action.ActionMapping mapping, org.apache.struts.action.ActionForm form, "
                + "javax.servlet.http.HttpServletRequest request, javax.servlet.http.HttpServletResponse response";
        return "package demo;\npublic class " + name + " extends " + parent + " {\n"
                + "  public org.apache.struts.action.ActionForward doFoo(" + params + ") { return null; }\n"
                + "  public org.apache.struts.action.ActionForward doBar(" + params + ") { return null; }\n}\n";
    }

    static List<Relationship> methodEdges(Index index, String source) {
        return index.relationships().stream().filter(e -> e.sourceId().equals(source) && e.targetId() != null
                && e.targetId().startsWith("java:method:")).toList();
    }

    static void assertEdge(Index index, String from, String to, String type, String state) {
        assertThat(index.relationships()).anySatisfy(e -> {
            assertThat(e.sourceId()).isEqualTo(from); assertThat(e.targetId()).isEqualTo(to);
            assertThat(e.type()).isEqualTo(type); assertThat(e.resolutionState()).isEqualTo(state);
        });
    }
}
