package com.oneil.legacy.framework;

import static org.assertj.core.api.Assertions.*;

import com.oneil.legacy.scan.RepositoryInventory;
import com.oneil.legacy.symbol.JavaIndexModel.*;
import com.oneil.legacy.symbol.JavaSymbolIndexer;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class SpringMvcIndexerTests {
    @TempDir Path root;
    @BeforeEach void fixture() throws Exception { copyFixture(root); }

    static void copyFixture(Path root) throws Exception {
        Path source = Path.of(SpringMvcIndexerTests.class.getResource("/fixtures/spring-mvc").toURI());
        try (var files = Files.walk(source)) {
            for (Path file : files.toList()) {
                Path target = root.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file)) Files.createDirectories(target); else Files.copy(file, target);
            }
        }
    }

    Index index() throws Exception {
        var inventory = new RepositoryInventory().collect(root);
        return new FrameworkIndexer().index(root, inventory, new JavaSymbolIndexer().index(root, inventory));
    }

    @Test void detectsControllersComposesPathsAndPreservesHttpMethods() throws Exception {
        var index = index();
        assertThat(routes(index, "/customers/{id}", "GET")).hasSize(2);
        assertThat(routes(index, "/customers", "POST")).singleElement();
        assertThat(routes(index, "/customers/all", "UNSPECIFIED")).singleElement();
        assertThat(routes(index, "/customers/{id}", "PUT")).singleElement();
        assertThat(routes(index, "/customers/{id}", "DELETE")).singleElement();
        assertThat(routes(index, "/customers/{id}", "PATCH")).singleElement();
        assertThat(routes(index, "/customers/mapped-a", "GET")).singleElement();
        assertThat(routes(index, "/customers/mapped-a", "POST")).singleElement();
        assertThat(routes(index, "/customers/mapped-b", "GET")).singleElement();
        assertThat(routes(index, "/customers/mapped-b", "POST")).singleElement();
        assertThat(routes(index, "/api/customers/status", "GET")).singleElement();
    }

    @Test void supportsMultiplePathsAndStaticFinalStringConstants() throws Exception {
        var index = index();
        assertThat(routes(index, "/customers/a", "GET")).singleElement();
        assertThat(routes(index, "/customers/b", "GET")).singleElement();
        var customer = routes(index, "/customers/{id}", "GET").stream()
                .filter(route -> route.stableId().contains("CustomerController#getCustomer")).findFirst().orElseThrow();
        assertThat(customer.resolutionState()).isEqualTo("RESOLVED");
        assertThat(index()).isEqualTo(index);
    }

    @Test void unresolvedExpressionsAndDuplicateRoutesRemainVisibleWithoutGuessing() throws Exception {
        var index = index();
        assertThat(routes(index, "<unresolved>", "GET")).singleElement().satisfies(route ->
                assertThat(route.resolutionState()).isEqualTo("UNRESOLVED"));
        assertThat(index.relationships()).filteredOn(edge -> edge.sourceId().contains("<unresolved>")
                && edge.type().equals("ROUTES_TO")).singleElement().satisfies(edge -> {
                    assertThat(edge.targetId()).isNull();
                    assertThat(edge.resolutionState()).isEqualTo("UNRESOLVED");
                    assertThat(edge.targetDescription()).contains("mapping unresolved").doesNotContain("dynamicPath");
                });
        assertThat(index.errors()).anyMatch(error -> error.code().equals("SPRING_MVC_MAPPING_UNRESOLVED"));
        assertThat(routes(index, "/customers/{id}", "GET")).extracting(Symbol::stableId)
                .anyMatch(id -> id.contains("CustomerController#getCustomer"))
                .anyMatch(id -> id.contains("DuplicateCustomerController#duplicate"));
    }

    @Test void routesTargetExistingJavaMethodsWithAnnotationEvidence() throws Exception {
        var index = index();
        String method = "java:method:demo.CustomerController#getCustomer(java.lang.String)";
        var route = routes(index, "/customers/{id}", "GET").stream()
                .filter(candidate -> candidate.stableId().contains("CustomerController#getCustomer")).findFirst().orElseThrow();
        assertThat(index.relationships()).anySatisfy(edge -> {
            assertThat(edge.sourceId()).isEqualTo(route.stableId());
            assertThat(edge.targetId()).isEqualTo(method);
            assertThat(edge.type()).isEqualTo("ROUTES_TO");
            assertThat(edge.resolutionState()).isEqualTo("RESOLVED");
            assertThat(edge.evidenceType()).isEqualTo("SPRING_MVC_ANNOTATION");
            assertThat(edge.sourcePath()).isEqualTo("src/demo/CustomerController.java");
            assertThat(edge.line()).isPositive();
            assertThat(edge.column()).isPositive();
        });
    }

    @Test void recordsObviousViewsButNeverInventsARestControllerView() throws Exception {
        var index = index();
        assertThat(index.relationships()).anyMatch(edge -> edge.sourceId().contains("CustomerController#getCustomer")
                && edge.type().equals("RENDERS") && "spring-mvc:view:customer/detail".equals(edge.targetId())
                && edge.resolutionState().equals("RESOLVED"));
        assertThat(index.relationships()).noneMatch(edge -> edge.sourceId().contains("RestCustomerController#status")
                && edge.type().equals("RENDERS"));
        assertThat(index.relationships()).noneMatch(edge -> edge.sourceId().contains("CustomerController#raw")
                && edge.type().equals("RENDERS"));
        assertThat(index.symbols()).noneMatch(symbol -> symbol.kind().equals("VIEW") && symbol.qualifiedName().equals("ok"));
        assertThat(index.symbols()).noneMatch(symbol -> symbol.kind().equals("VIEW") && symbol.qualifiedName().equals("raw-body"));
    }

    @Test void beanNameUrlHandlerMappingUsesOnlyTheBoundedHandleRequestConvention() throws Exception {
        var index = index();
        var route = routes(index, "/legacy-customers", "UNSPECIFIED").getFirst();
        assertThat(route.signature()).contains("BeanNameUrlHandlerMapping");
        assertThat(index.relationships()).filteredOn(edge -> edge.sourceId().equals(route.stableId())
                && edge.type().equals("ROUTES_TO")).singleElement().satisfies(edge -> {
                    assertThat(edge.targetId()).isEqualTo("java:method:demo.LegacyController#handleRequest()");
                    assertThat(edge.resolutionState()).isEqualTo("INFERRED");
                    assertThat(edge.evidenceType()).isEqualTo("XML");
                });
    }

    @Test void malformedJavaIsLocalizedAndOtherRoutesStillIndex() throws Exception {
        var index = index();
        assertThat(index.errors()).anyMatch(error -> error.relativePath().endsWith("BrokenController.java")
                && error.code().equals("JAVA_PARSE"));
        assertThat(routes(index, "/customers", "POST")).hasSize(1);
    }

    static List<Symbol> routes(Index index, String path, String method) {
        return index.symbols().stream().filter(symbol -> symbol.kind().equals("ROUTE")
                && symbol.qualifiedName().equals(path)
                && symbol.signature() != null && symbol.signature().contains("httpMethod=" + method)).toList();
    }
}
