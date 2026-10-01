package com.oneil.legacy.grails;

import static org.assertj.core.api.Assertions.*;

import com.oneil.legacy.scan.RepositoryInventory;
import com.oneil.legacy.symbol.JavaIndexModel.*;
import com.oneil.legacy.symbol.JavaSymbolIndexer;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Grails 2.6 layout: grails-app/conf/UrlMappings.groovy, named mappings and legacy DSL forms. */
class Grails26IndexerTests {
    @TempDir Path root;
    static final String MAPPINGS = "grails-app/conf/UrlMappings.groovy";
    static final String CONTROLLER = "groovy:type:demo.CustomerController";
    static final String SHOW = "groovy:method:demo.CustomerController#show(Long)";
    static final String SERVICE = "groovy:type:demo.CustomerService";
    static final String FINDER = "groovy:method:demo.CustomerService#findByLastName(String)";
    static final String CUSTOMER = "groovy:type:demo.Customer";
    static final String RESOURCES = "grails-app/conf/spring/resources.groovy";

    @BeforeEach void fixture() throws Exception { copyFixture(root); }

    static void copyFixture(Path root) throws Exception {
        Path source = Path.of(Grails26IndexerTests.class.getResource("/fixtures/grails26").toURI());
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

    @Test void legacyUrlMappingsResolveActionsAndKeepNamesAsEvidence() throws Exception {
        var index = index();
        assertThat(index.symbols()).extracting(Symbol::stableId)
                .contains(route("/customer/$id"), route("/customers"), route("/customer"), route("/product/$id?"), route("500"));

        assertThat(single(index, route("/customer/$id"), "ROUTES_TO").targetId()).isEqualTo(SHOW);
        assertThat(single(index, route("/customer/$id"), "ROUTES_TO").resolutionState()).isEqualTo("RESOLVED");

        // Grails 2.x named mapping: the route is indexed under its URI and keeps the mapping name.
        var named = single(index, route("/customers"), "ROUTES_TO");
        assertThat(named.targetId()).isEqualTo("groovy:method:demo.CustomerController#list()");
        assertThat(named.resolutionState()).isEqualTo("RESOLVED");
        assertThat(index.symbols()).filteredOn(s -> s.stableId().equals(route("/customers"))).singleElement()
                .satisfies(s -> assertThat(s.signature()).isEqualTo("name=customerList; controller=customer; action=list"));

        assertThat(single(index, route("/customer"), "ROUTES_TO").resolutionState()).isEqualTo("INFERRED");
        assertThat(single(index, route("/$controller/$action?/$id?"), "ROUTES_TO").resolutionState()).isEqualTo("UNRESOLVED");
    }

    @Test void legacyDslFormsDoNotInventRoutesOrMethods() throws Exception {
        var index = index();
        // The "name" DSL call is unwrapped, and the nested constraint closure is not a route.
        assertThat(index.symbols()).extracting(Symbol::stableId).doesNotContain(route("name"), route("id"));
        assertThat(index.symbols()).noneMatch(s -> s.stableId().contains("#id") && s.kind().equals("ROUTE"));
        // "500"(view: ...) is an explicit mapping without a controller target.
        assertThat(edges(index, route("500"), "ROUTES_TO")).isEmpty();
        assertThat(index.symbols()).filteredOn(s -> s.stableId().equals(route("/product/$id?"))).singleElement();

        // A Grails 2.x closure action is an explicit declaration but not a method: no invented METHOD symbol,
        // and the route keeps the resolved controller with an honest note about the action.
        assertThat(index.symbols()).extracting(Symbol::stableId)
                .doesNotContain("groovy:method:demo.LegacyClosureController#list()");
        var closureAction = single(index, route("/legacy/list"), "ROUTES_TO");
        assertThat(closureAction.targetId()).isEqualTo("groovy:type:demo.LegacyClosureController");
        assertThat(closureAction.targetDescription()).contains("action=list", "not statically indexed");
    }

    @Test void servicesInjectionAndUsageMatchTheSlice8Rules() throws Exception {
        var index = index();
        // static transactional/allowedMethods are not dependencies
        assertThat(edges(index, CONTROLLER, "INJECTS")).singleElement().satisfies(e -> {
            assertThat(e.targetId()).isEqualTo(SERVICE);
            assertThat(e.resolutionState()).isEqualTo("INFERRED");
        });
        assertThat(single(index, SHOW, "CALLS").targetId()).isEqualTo(FINDER);
        assertThat(single(index, SHOW, "CALLS").resolutionState()).isEqualTo("RESOLVED");
        assertThat(single(index, "groovy:method:demo.CustomerController#list()", "CALLS").targetId())
                .isEqualTo("groovy:method:demo.CustomerService#findAllCustomers()");
    }

    @Test void legacyGormMappingAndDynamicFindersProduceTheSameEvidence() throws Exception {
        var index = index();
        var explicit = single(index, CUSTOMER, "MAPS_TO_TABLE");
        assertThat(explicit.targetId()).isEqualTo("db:table:CUSTOMER");
        assertThat(explicit.resolutionState()).isEqualTo("RESOLVED");
        // The id/version entries in the 2.x mapping closure are ignored, not mistaken for the table.
        assertThat(index.symbols()).extracting(Symbol::stableId).contains("db:table:CUSTOMER")
                .doesNotContain("db:table=true");

        var conventional = single(index, "groovy:type:demo.CustomerOrder", "MAPS_TO_TABLE");
        assertThat(conventional.targetId()).isEqualTo("db:table:customer_order");
        assertThat(conventional.resolutionState()).isEqualTo("INFERRED");

        assertThat(edges(index, FINDER, "READS_TABLE")).singleElement().satisfies(e -> {
            assertThat(e.targetId()).isEqualTo("db:table:CUSTOMER");
            assertThat(e.resolutionState()).isEqualTo("INFERRED");
        });
        assertThat(edges(index, "groovy:method:demo.CustomerService#findAllCustomers()", "READS_TABLE"))
                .singleElement().satisfies(e -> assertThat(e.targetId()).isEqualTo("db:table:CUSTOMER"));
    }

    @Test void legacyResourcesGroovyWiringAndAmbiguityAreResolvedConsistently() throws Exception {
        var index = index();
        var bean = "spring:bean:" + RESOURCES + "#customerService";
        var dao = "spring:bean:" + RESOURCES + "#customerDao";
        assertThat(single(index, bean, "WIRES_TO").targetId()).isEqualTo(SERVICE);
        assertThat(single(index, bean, "INJECTS").targetId()).isEqualTo(dao);
        assertThat(single(index, dao, "WIRES_TO").targetId()).isEqualTo("groovy:type:demo.CustomerDao");
        assertThat(single(index, dao, "INJECTS").targetDescription()).contains("sessionFactory");

        var ambiguous = "groovy:type:demo.LegacyAmbiguousController";
        var injection = single(index, ambiguous, "INJECTS");
        assertThat(injection.targetId()).isNull();
        assertThat(injection.resolutionState()).isEqualTo("UNRESOLVED");
        assertThat(injection.targetDescription()).contains("demo.AmbiguousService", "demo.legacy.AmbiguousService", "count=2");
        assertThat(single(index, "groovy:method:demo.LegacyAmbiguousController#index()", "CALLS").resolutionState())
                .isEqualTo("UNRESOLVED");
    }

    @Test void malformedLegacyGroovyIsLocalizedAndEvidenceIsDeterministic() throws Exception {
        var first = index();
        assertThat(first.errors()).extracting(com.oneil.legacy.scan.ScanModel.AnalysisError::code).contains("GROOVY_PARSE");
        assertThat(first.errors()).filteredOn(e -> e.code().equals("GROOVY_PARSE"))
                .singleElement().satisfies(e -> assertThat(e.relativePath()).endsWith("BrokenLegacyController.groovy"));
        assertThat(first.errors()).allSatisfy(e -> assertThat(e.stage()).isEqualTo("GROOVY"));
        assertThat(first.symbols()).extracting(Symbol::stableId).contains(CONTROLLER, SERVICE, CUSTOMER);

        assertThat(first.relationships()).filteredOn(e -> e.evidenceType().equals("GROOVY")).isNotEmpty()
                .allSatisfy(e -> {
                    assertThat(e.sourcePath()).endsWith(".groovy");
                    assertThat(e.line()).isPositive();
                    assertThat(e.column()).isPositive();
                });
        assertThat(index().symbols()).isEqualTo(first.symbols());
        assertThat(index().relationships()).isEqualTo(first.relationships());
    }

    @Test void noNewKindsOrRelationshipTypesAreIntroduced() throws Exception {
        var index = index();
        Set<String> kinds = Set.of("PACKAGE", "CLASS", "INTERFACE", "METHOD", "FIELD", "CONTEXT", "BEAN", "ROUTE", "FORM", "FORWARD",
                "VIEW", "SERVLET", "DATABASE_TABLE", "QUERY_ARTIFACT");
        Set<String> types = Set.of("CONTAINS", "IMPORTS", "EXTENDS", "IMPLEMENTS", "OVERRIDES", "CALLS", "ROUTES_TO", "FORWARDS_TO",
                "RENDERS", "INJECTS", "WIRES_TO", "READS_TABLE", "WRITES_TABLE", "MAPS_TO_TABLE", "DECLARES_QUERY", "EXECUTES_QUERY");
        assertThat(index.symbols()).allSatisfy(s -> assertThat(kinds).contains(s.kind()));
        assertThat(index.relationships()).allSatisfy(e -> assertThat(types).contains(e.type()));
    }
}
