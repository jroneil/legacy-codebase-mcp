package com.oneil.legacy.grails;

import static org.assertj.core.api.Assertions.*;

import com.oneil.legacy.scan.RepositoryInventory;
import com.oneil.legacy.symbol.JavaIndexModel.*;
import com.oneil.legacy.symbol.JavaSymbolIndexer;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class GrailsIndexerTests {
    @TempDir Path root;
    static final String MAPPINGS = "grails-app/controllers/demo/UrlMappings.groovy";
    static final String CONTROLLER = "groovy:type:demo.CustomerController";
    static final String SHOW = "groovy:method:demo.CustomerController#show(Long)";
    static final String SERVICE = "groovy:type:demo.CustomerService";
    static final String FINDER = "groovy:method:demo.CustomerService#findByLastName(String)";
    static final String CUSTOMER = "groovy:type:demo.Customer";
    static final String RESOURCES = "grails-app/conf/spring/resources.groovy";

    @BeforeEach void fixture() throws Exception { copyFixture(root); }

    static void copyFixture(Path root) throws Exception {
        Path source = Path.of(GrailsIndexerTests.class.getResource("/fixtures/grails").toURI());
        try (var files = Files.walk(source)) {
            for (var file : files.toList()) {
                var target = root.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file)) Files.createDirectories(target); else Files.copy(file, target);
            }
        }
    }

    Index index() throws Exception {
        var inventory = new RepositoryInventory().collect(root);
        return new GrailsIndexer().index(root, inventory, new JavaSymbolIndexer().index(root, inventory));
    }

    static List<Relationship> edges(Index index, String source, String type) {
        return index.relationships().stream().filter(e -> e.sourceId().equals(source) && e.type().equals(type)).toList();
    }

    static Relationship single(Index index, String source, String type) {
        return assertThat(edges(index, source, type)).as(source + " " + type).hasSize(1).actual().getFirst();
    }

    static String route(String uri) { return "grails:route:" + MAPPINGS + "#" + uri; }

    @Test void urlMappingsResolveExplicitActionsDefaultsAndResources() throws Exception {
        var index = index();
        assertThat(index.symbols()).extracting(Symbol::stableId).contains(route("/customer/$id"), route("/customer"), route("/books"), route("/ping"));
        var context = "groovy:context:" + MAPPINGS;
        assertThat(edges(index, context, "CONTAINS")).extracting(Relationship::targetId).contains(route("/customer/$id"), route("/ping"));

        var explicit = single(index, route("/customer/$id"), "ROUTES_TO");
        assertThat(explicit.targetId()).isEqualTo(SHOW);
        assertThat(explicit.resolutionState()).isEqualTo("RESOLVED");

        // No action named in the mapping: the Grails default "index" is a convention, not source evidence.
        var defaulted = single(index, route("/customer"), "ROUTES_TO");
        assertThat(defaulted.targetId()).isEqualTo("groovy:method:demo.CustomerController#index()");
        assertThat(defaulted.resolutionState()).isEqualTo("INFERRED");

        var resources = single(index, route("/books"), "ROUTES_TO");
        assertThat(resources.targetId()).isEqualTo("groovy:type:demo.BookController");
        assertThat(resources.resolutionState()).isEqualTo("INFERRED");

        // A nested group closure is still walked; the declared route text is preserved.
        assertThat(single(index, route("/ping"), "ROUTES_TO").targetId()).isEqualTo("groovy:method:demo.CustomerController#ping()");

        // Dynamic controller/action expressions stay unresolved.
        var dynamic = single(index, route("/legacy/$controller/$action?/$id?"), "ROUTES_TO");
        assertThat(dynamic.targetId()).isNull();
        assertThat(dynamic.resolutionState()).isEqualTo("UNRESOLVED");
        assertThat(dynamic.targetDescription()).contains("Dynamic controller/action");
    }

    @Test void controllersActionsAndInjectionAreIndexed() throws Exception {
        var index = index();
        assertThat(index.symbols()).filteredOn(s -> s.stableId().equals(CONTROLLER)).singleElement()
                .satisfies(s -> { assertThat(s.kind()).isEqualTo("CLASS"); assertThat(s.qualifiedName()).isEqualTo("demo.CustomerController"); });
        assertThat(index.symbols()).extracting(Symbol::stableId)
                .contains(SHOW, "groovy:method:demo.CustomerController#index()", "groovy:method:demo.CustomerController#ping()", SERVICE);

        // Untyped property resolved by the Grails naming convention.
        assertThat(edges(index, CONTROLLER, "INJECTS")).filteredOn(e -> e.targetId() != null && e.resolutionState().equals("INFERRED"))
                .singleElement().satisfies(e -> assertThat(e.targetId()).isEqualTo(SERVICE));
        // Explicitly declared type is source evidence, and the property is still unresolved-safe on ambiguity.
        assertThat(edges(index, CONTROLLER, "INJECTS")).filteredOn(e -> e.targetId() != null && e.resolutionState().equals("RESOLVED"))
                .singleElement().satisfies(e -> assertThat(e.targetId()).isEqualTo(SERVICE));
    }

    @Test void serviceUsageAndDynamicFinderProduceInferredReads() throws Exception {
        var index = index();
        var call = single(index, SHOW, "CALLS");
        assertThat(call.targetId()).isEqualTo(FINDER);
        assertThat(call.resolutionState()).isEqualTo("RESOLVED");

        // Customer.findByLastName(name) on a statically determined domain: GORM convention, so INFERRED.
        assertThat(edges(index, FINDER, "READS_TABLE")).singleElement().satisfies(e -> {
            assertThat(e.targetId()).isEqualTo("db:table:CUSTOMER");
            assertThat(e.resolutionState()).isEqualTo("INFERRED");
            assertThat(e.targetDescription()).contains("gorm finder findByLastName", "demo.Customer");
        });
        assertThat(edges(index, FINDER, "CALLS")).singleElement().satisfies(e -> {
            assertThat(e.targetId()).isEqualTo(CUSTOMER);
            assertThat(e.resolutionState()).isEqualTo("INFERRED");
        });
        // A second GORM finder form is recognized the same way.
        assertThat(edges(index, "groovy:method:demo.CustomerService#listAll()", "READS_TABLE"))
                .singleElement().satisfies(e -> assertThat(e.targetId()).isEqualTo("db:table:CUSTOMER"));
    }

    @Test void domainsMapToTablesExplicitlyAndByConvention() throws Exception {
        var index = index();
        var explicit = single(index, CUSTOMER, "MAPS_TO_TABLE");
        assertThat(explicit.targetId()).isEqualTo("db:table:CUSTOMER");
        assertThat(explicit.resolutionState()).isEqualTo("RESOLVED");
        assertThat(explicit.targetDescription()).isEqualTo("explicit GORM table mapping");

        var conventional = single(index, "groovy:type:demo.CustomerOrder", "MAPS_TO_TABLE");
        assertThat(conventional.targetId()).isEqualTo("db:table:customer_order");
        assertThat(conventional.resolutionState()).isEqualTo("INFERRED");
        assertThat(index.symbols()).filteredOn(s -> s.stableId().equals("db:table:customer_order"))
                .singleElement().satisfies(s -> assertThat(s.kind()).isEqualTo("DATABASE_TABLE"));
    }

    @Test void resourcesGroovyBeanWiringIsIndexed() throws Exception {
        var index = index();
        var bean = "spring:bean:" + RESOURCES + "#customerService";
        var dao = "spring:bean:" + RESOURCES + "#customerDao";
        assertThat(index.symbols()).extracting(Symbol::stableId).contains(bean, dao, "groovy:context:" + RESOURCES);
        assertThat(single(index, bean, "WIRES_TO").targetId()).isEqualTo(SERVICE);
        assertThat(single(index, bean, "INJECTS").targetId()).isEqualTo(dao);
        assertThat(single(index, dao, "WIRES_TO").targetId()).isEqualTo("groovy:type:demo.CustomerDao");
        // ref('sessionFactory') is not defined in this file: the reference stays unresolved.
        var unresolved = single(index, dao, "INJECTS");
        assertThat(unresolved.targetId()).isNull();
        assertThat(unresolved.resolutionState()).isEqualTo("UNRESOLVED");
        assertThat(unresolved.targetDescription()).contains("sessionFactory");
    }

    @Test void ambiguousInjectionAndMissingDependencyStayUnresolved() throws Exception {
        var index = index();
        var ambiguous = "groovy:type:demo.AmbiguousController";
        var injection = single(index, ambiguous, "INJECTS");
        assertThat(injection.targetId()).isNull();
        assertThat(injection.resolutionState()).isEqualTo("UNRESOLVED");
        assertThat(injection.targetDescription()).contains("demo.AmbiguousService", "demo.other.AmbiguousService", "count=2");
        // A call through an ambiguous property cannot pick a target.
        var call = single(index, "groovy:method:demo.AmbiguousController#index()", "CALLS");
        assertThat(call.targetId()).isNull();
        assertThat(call.resolutionState()).isEqualTo("UNRESOLVED");

        var missing = single(index, "groovy:type:demo.LegacyController", "INJECTS");
        assertThat(missing.targetId()).isNull();
        assertThat(missing.resolutionState()).isEqualTo("UNRESOLVED");
        assertThat(missing.targetDescription()).contains("missingService");
    }

    @Test void metaprogrammingAndMalformedGroovyAreLocalized() throws Exception {
        var index = index();
        assertThat(index.errors()).extracting(com.oneil.legacy.scan.ScanModel.AnalysisError::code)
                .contains("GRAILS_METAPROGRAMMING", "GROOVY_PARSE");
        assertThat(index.errors()).filteredOn(e -> e.code().equals("GROOVY_PARSE"))
                .singleElement().satisfies(e -> assertThat(e.relativePath()).endsWith("Broken.groovy"));
        assertThat(index.errors()).allSatisfy(e -> assertThat(e.stage()).isEqualTo("GROOVY"));
        var metaprogrammed = edges(index, "groovy:method:demo.SneakyController#index()", "CALLS");
        assertThat(metaprogrammed).anyMatch(e -> e.targetId() == null && e.resolutionState().equals("UNRESOLVED"));
        // Malformed source does not stop the rest of the index.
        assertThat(index.symbols()).extracting(Symbol::stableId).contains(CONTROLLER, SERVICE);
    }

    @Test void evidenceLocationsAreGroovyAndDeterministic() throws Exception {
        var first = index();
        var second = index();
        assertThat(second.symbols()).isEqualTo(first.symbols());
        assertThat(second.relationships()).isEqualTo(first.relationships());
        assertThat(first.relationships()).filteredOn(e -> e.evidenceType().equals("GROOVY")).isNotEmpty()
                .allSatisfy(e -> {
                    assertThat(e.sourcePath()).endsWith(".groovy");
                    assertThat(e.line()).isPositive();
                    assertThat(e.column()).isPositive();
                });
    }

    @Test void malformedResourcesConfigurationIsReportedLocally() throws Exception {
        Path other = root.resolve("malformed-resources");
        Files.createDirectories(other.resolve("grails-app/conf/spring"));
        Files.writeString(other.resolve("grails-app/conf/spring/resources.groovy"), "something = 1\n");
        var inventory = new RepositoryInventory().collect(other);
        var index = new GrailsIndexer().index(other, inventory, new JavaSymbolIndexer().index(other, inventory));
        assertThat(index.errors()).extracting(com.oneil.legacy.scan.ScanModel.AnalysisError::code)
                .contains("GRAILS_RESOURCES_UNSUPPORTED");
    }

    @Test void normalizedModelIsReusedWithoutNewKindsOrTypes() throws Exception {
        var index = index();
        Set<String> kinds = Set.of("PACKAGE", "CLASS", "INTERFACE", "METHOD", "FIELD", "CONTEXT", "BEAN", "ROUTE", "FORM", "FORWARD",
                "VIEW", "SERVLET", "DATABASE_TABLE", "QUERY_ARTIFACT");
        Set<String> types = Set.of("CONTAINS", "IMPORTS", "EXTENDS", "IMPLEMENTS", "OVERRIDES", "CALLS", "ROUTES_TO", "FORWARDS_TO",
                "RENDERS", "INJECTS", "WIRES_TO", "READS_TABLE", "WRITES_TABLE", "MAPS_TO_TABLE", "DECLARES_QUERY", "EXECUTES_QUERY");
        assertThat(index.symbols()).allSatisfy(s -> assertThat(kinds).contains(s.kind()));
        assertThat(index.relationships()).allSatisfy(e -> assertThat(types).contains(e.type()));
        assertThat(index.symbols()).extracting(Symbol::stableId).anyMatch(id -> id.startsWith("groovy:type:"))
                .anyMatch(id -> id.startsWith("groovy:method:")).anyMatch(id -> id.startsWith("grails:route:"));
    }
}
