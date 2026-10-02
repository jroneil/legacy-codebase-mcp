package com.oneil.legacy.framework;

import static org.assertj.core.api.Assertions.*;

import com.oneil.legacy.scan.RepositoryInventory;
import com.oneil.legacy.symbol.JavaIndexModel.*;
import com.oneil.legacy.symbol.JavaSymbolIndexer;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class SpringAnnotationIndexerTests {
    @TempDir Path root;

    @BeforeEach void fixture() throws Exception { copyFixture(root); }

    static void copyFixture(Path root) throws Exception {
        Path source = Path.of(SpringAnnotationIndexerTests.class.getResource("/fixtures/spring-boot").toURI());
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

    @Test void detectsStereotypesAndUsesExplicitOrConventionalBeanNames() throws Exception {
        var index = index();
        assertThat(bean(index, "customerService")).satisfies(bean -> {
            assertThat(bean.stableId()).isEqualTo("spring:bean:annotation:demo.CustomerService#customerService");
            assertThat(bean.resolutionState()).isEqualTo("INFERRED");
            assertThat(bean.signature()).contains("stereotype=Service", "active=true");
        });
        assertThat(bean(index, "auditServiceV2").resolutionState()).isEqualTo("RESOLVED");
        assertThat(bean(index, "legacyRepository").resolutionState()).isEqualTo("RESOLVED");
        assertThat(bean(index, "demoApplication").signature()).contains("SpringBootApplication");
        assertThat(index.relationships()).anyMatch(edge -> edge.sourceId().endsWith("#customerService")
                && "java:type:demo.CustomerService".equals(edge.targetId())
                && edge.type().equals("WIRES_TO") && edge.evidenceType().equals("SPRING_COMPONENT"));
    }

    @Test void appliesDefaultAndExplicitComponentScanBoundariesWithoutActivatingOutsiders() throws Exception {
        var index = index();
        assertThat(bean(index, "sharedComponent").resolutionState()).isEqualTo("INFERRED");
        var outside = bean(index, "outsideService");
        assertThat(outside.resolutionState()).isEqualTo("UNRESOLVED");
        assertThat(index.relationships()).filteredOn(edge -> edge.sourceId().equals(outside.stableId())
                && edge.type().equals("WIRES_TO")).singleElement().satisfies(edge -> {
                    assertThat(edge.targetId()).isNull();
                    assertThat(edge.targetDescription()).contains("outside known component-scan boundary");
                });
        assertThat(index.errors()).anyMatch(error -> error.code().equals("SPRING_COMPONENT_OUTSIDE_SCAN"));
    }

    @Test void indexesConfigurationBeanMethodsNamesAliasesReturnTypesAndDynamicNames() throws Exception {
        var index = index();
        var customerClient = bean(index, "customerClient");
        assertThat(customerClient.signature()).contains("framework=Spring Bean",
                "factoryMethod=java:method:demo.AppConfig#customerClient()", "legacyClient");
        assertThat(index.relationships()).anyMatch(edge -> edge.sourceId().equals(customerClient.stableId())
                && "java:type:demo.CustomerClient".equals(edge.targetId())
                && edge.type().equals("WIRES_TO") && edge.evidenceType().equals("SPRING_BEAN"));
        assertThat(bean(index, "clockService").resolutionState()).isEqualTo("INFERRED");
        assertThat(bean(index, "singleClient").resolutionState()).isEqualTo("RESOLVED");
        assertThat(index.symbols()).anyMatch(symbol -> symbol.kind().equals("BEAN")
                && symbol.stableId().contains("dynamicClient")
                && symbol.resolutionState().equals("UNRESOLVED"));
        assertThat(index.errors()).anyMatch(error -> error.code().equals("SPRING_BEAN_NAME_UNRESOLVED"));
    }

    @Test void resolvesConstructorFieldSetterInjectAutowiredAndResourceWiring() throws Exception {
        var index = index();
        String service = bean(index, "customerService").stableId();
        assertInjection(index, service, "customerRepository", "constructor-parameter=repository", "RESOLVED");
        assertInjection(index, service, "auditServiceV2", "field=auditService", "RESOLVED");
        assertInjection(index, service, "customerClient", "field=customerClient", "RESOLVED");
        assertInjection(index, service, "clockService", "method=setClockService", "RESOLVED");

        String controller = bean(index, "customerController").stableId();
        assertInjection(index, controller, "customerService", "constructor-parameter=customerService", "INFERRED");
    }

    @Test void preservesAmbiguousAndMissingInjectionWithoutSelectingACandidate() throws Exception {
        var index = index();
        String service = bean(index, "customerService").stableId();
        assertThat(index.relationships()).filteredOn(edge -> edge.sourceId().equals(service)
                && edge.type().equals("INJECTS") && edge.targetId() == null)
                .anySatisfy(edge -> assertThat(edge.targetDescription())
                        .contains("field=notifier", "emailNotifier", "smsNotifier"))
                .anySatisfy(edge -> assertThat(edge.targetDescription())
                        .contains("field=missingGateway", "candidates=[none]"));
        assertThat(index.errors()).anyMatch(error -> error.code().equals("SPRING_INJECTION_AMBIGUOUS"))
                .anyMatch(error -> error.code().equals("SPRING_INJECTION_MISSING"));
    }

    @Test void indexesSpringDataEntityAndExplicitJpaTableWithNarrowDerivedMethods() throws Exception {
        var index = index();
        String repository = "java:type:demo.CustomerRepository";
        String entity = "java:type:demo.Customer";
        String table = "db:table:CUSTOMER";
        assertThat(index.symbols()).anyMatch(symbol -> symbol.stableId().equals(table)
                && symbol.kind().equals("DATABASE_TABLE"));
        assertEdge(index, entity, table, "MAPS_TO_TABLE", "RESOLVED", "JPA_ANNOTATION");
        assertEdge(index, repository, entity, "WIRES_TO", "RESOLVED", "SPRING_DATA");
        assertEdge(index, "java:method:demo.CustomerRepository#findById(java.lang.Long)", table,
                "READS_TABLE", "INFERRED", "SPRING_DATA_DERIVED_METHOD");
        assertEdge(index, "java:method:demo.CustomerRepository#save(demo.Customer)", table,
                "WRITES_TABLE", "INFERRED", "SPRING_DATA_DERIVED_METHOD");
    }

    @Test void keepsEvidenceLocationsLocalizesMalformedSourcesAndProducesDeterministicIds() throws Exception {
        var index = index();
        assertThat(index.relationships()).filteredOn(edge -> Set.of("SPRING_COMPONENT", "SPRING_BEAN",
                        "SPRING_INJECTION", "SPRING_DATA", "JPA_ANNOTATION",
                        "SPRING_DATA_DERIVED_METHOD").contains(edge.evidenceType()))
                .allSatisfy(edge -> {
                    assertThat(edge.sourcePath()).startsWith("src/main/java/");
                    assertThat(edge.line()).isPositive();
                    assertThat(edge.column()).isPositive();
                });
        assertThat(index.errors()).anyMatch(error -> error.relativePath().endsWith("BrokenConfiguration.java")
                && error.code().equals("JAVA_PARSE"));
        assertThat(index()).isEqualTo(index);
    }

    private static Symbol bean(Index index, String name) {
        return index.symbols().stream().filter(symbol -> symbol.kind().equals("BEAN")
                && symbol.simpleName().equals(name)).findFirst().orElseThrow();
    }

    private static void assertInjection(Index index, String source, String targetName,
                                        String description, String state) {
        String target = bean(index, targetName).stableId();
        assertThat(index.relationships()).filteredOn(edge -> edge.sourceId().equals(source)
                && target.equals(edge.targetId()) && edge.type().equals("INJECTS"))
                .singleElement().satisfies(edge -> {
                    assertThat(edge.targetDescription()).contains(description);
                    assertThat(edge.resolutionState()).isEqualTo(state);
                    assertThat(edge.evidenceType()).isEqualTo("SPRING_INJECTION");
                });
    }

    private static void assertEdge(Index index, String source, String target, String type,
                                   String state, String evidence) {
        assertThat(index.relationships()).anySatisfy(edge -> {
            if (!edge.sourceId().equals(source) || !target.equals(edge.targetId()) || !edge.type().equals(type)) return;
            assertThat(edge.resolutionState()).isEqualTo(state);
            assertThat(edge.evidenceType()).isEqualTo(evidence);
        });
        assertThat(index.relationships()).anyMatch(edge -> edge.sourceId().equals(source)
                && target.equals(edge.targetId()) && edge.type().equals(type)
                && edge.resolutionState().equals(state) && edge.evidenceType().equals(evidence));
    }
}
