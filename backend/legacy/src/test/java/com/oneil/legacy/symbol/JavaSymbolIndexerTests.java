package com.oneil.legacy.symbol;

import static com.oneil.legacy.symbol.JavaIndexModel.*;
import static org.assertj.core.api.Assertions.*;

import com.oneil.legacy.scan.RepositoryInventory;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaSymbolIndexerTests {
    @TempDir Path root;
    private final JavaSymbolIndexer indexer = new JavaSymbolIndexer();
    private Index fixture() throws Exception {
        Path source = Path.of(getClass().getResource("/fixtures/java-index").toURI());
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = root.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) Files.createDirectories(destination); else Files.copy(path, destination);
            }
        }
        return index();
    }
    private Index index() throws Exception { return indexer.index(root, new RepositoryInventory().collect(root)); }
    private void write(String file, String source) throws Exception { Files.writeString(root.resolve(file), source); }

    @Test
    void declarationsHaveCanonicalOverloadAwareIdsAndSourceLocations() throws Exception {
        var result = fixture();
        assertThat(result.symbols()).extracting(Symbol::stableId).contains(
                "java:package:demo", "java:type:demo.Worker", "java:type:demo.Service", "java:type:demo.Service.Nested",
                "java:field:demo.Service#count", "java:method:demo.Service#run(java.lang.Long)",
                "java:method:demo.Service#ping(int)", "java:method:demo.Service#ping(java.lang.String)",
                "java:method:other.Service#ping(int)");
        assertThat(result.symbols()).allSatisfy(s -> {
            assertThat(s.startLine()).isPositive();
            assertThat(s.startColumn()).isPositive();
            assertThat(s.endLine()).isGreaterThanOrEqualTo(s.startLine());
            assertThat(s.sourcePath()).doesNotStartWith("/");
        });
        assertThat(result.symbols()).filteredOn(s -> s.stableId().equals("java:type:demo.Worker")).singleElement()
                .extracting(Symbol::kind).isEqualTo("INTERFACE");
    }

    @Test
    void allRequiredEdgesHaveCorrectTargetsStatesAndEvidence() throws Exception {
        var result = fixture();
        assertEdge(result, "java:type:demo.Service", "java:type:demo.Base", "EXTENDS");
        assertEdge(result, "java:type:demo.Service", "java:type:demo.Worker", "IMPLEMENTS");
        assertEdge(result, "java:package:demo", "java:type:demo.Service", "CONTAINS");
        assertEdge(result, "java:type:demo.Service", "java:field:demo.Service#count", "CONTAINS");
        assertEdge(result, "java:type:demo.Caller", "java:type:other.Service", "IMPORTS");
        assertEdge(result, "java:method:demo.Service#run(java.lang.Long)", "java:method:demo.Base#run(java.lang.Long)", "OVERRIDES");
        assertEdge(result, "java:method:demo.Service#run(java.lang.Long)", "java:method:demo.Worker#run(java.lang.Long)", "OVERRIDES");
        assertEdge(result, "java:method:demo.Service#run(java.lang.Long)", "java:method:demo.Service#ping(int)", "CALLS");
        assertEdge(result, "java:method:demo.Caller#invoke(other.Service)", "java:method:other.Service#ping(int)", "CALLS");
        assertThat(result.relationships()).allSatisfy(edge -> {
            assertThat(edge.line()).isPositive();
            assertThat(edge.column()).isPositive();
            assertThat(edge.evidenceType()).startsWith("JAVA_");
        });
    }

    @Test
    void missingJarAndMalformedSourcePreserveUsefulPartialEvidence() throws Exception {
        var result = fixture();
        assertThat(result.errors()).anyMatch(e -> e.code().equals("JAVA_PARSE") && e.relativePath().equals("broken/Broken.java"));
        assertThat(result.symbols()).extracting(Symbol::stableId).contains("java:type:legacy.Missing",
                "java:method:legacy.Missing#overload(?Library)");
        assertThat(result.relationships()).anySatisfy(edge -> {
            assertThat(edge.sourceId()).isEqualTo("java:type:legacy.Missing");
            assertThat(edge.type()).isEqualTo("EXTENDS");
            assertThat(edge.resolutionState()).isEqualTo("UNRESOLVED");
            assertThat(edge.targetId()).isNull();
        });
        assertThat(result.relationships()).anyMatch(e -> e.type().equals("OVERRIDES") && e.resolutionState().equals("INFERRED"));
        assertThat(result.relationships()).filteredOn(e -> e.resolutionState().equals("UNRESOLVED")).allSatisfy(e -> {
            assertThat(e.targetId()).isNull();
            assertThat(e.targetDescription()).isNotBlank();
        });
        assertThat(result.toString()).doesNotContain("fixture-secret-must-not-escape");
    }

    @Test
    void identicalScansProduceIdenticalSymbolsAndRelationships() throws Exception {
        var first = fixture();
        assertThat(index()).isEqualTo(first);
    }

    @Test
    void java21SyntaxAndVarargsAreSupportedOnJava21() throws Exception {
        write("Modern.java", """
                package modern;
                class Modern {
                    int length(Object value) { return switch (value) { case String s -> s.length(); default -> 0; }; }
                    void values(String... names) {}
                    void use() { values("one", "two"); }
                }
                """);
        var result = index();
        assertThat(result.errors()).isEmpty();
        assertThat(result.symbols()).extracting(Symbol::stableId).contains("java:method:modern.Modern#values(java.lang.String[])");
        assertEdge(result, "java:method:modern.Modern#use()", "java:method:modern.Modern#values(java.lang.String[])", "CALLS");
    }

    @Test
    void rereadCannotEscapeViaSymlinkOrIndexChangedContent() throws Exception {
        write("Safe.java", "class Safe {}");
        var inventory = new RepositoryInventory().collect(root);
        write("Safe.java", "class Replaced {}");
        assertThat(indexer.index(root, inventory).errors()).extracting(e -> e.code()).containsExactly("JAVA_READ");
        Files.delete(root.resolve("Safe.java"));
        Path external = Files.createTempFile("slice2-external-", ".java");
        try {
            Files.writeString(external, "class External {}");
            Files.createSymbolicLink(root.resolve("Safe.java"), external);
            var result = indexer.index(root, inventory);
            assertThat(result.symbols()).isEmpty();
            assertThat(result.errors()).extracting(e -> e.code()).containsExactly("JAVA_READ");
        } finally { Files.delete(external); }
    }

    @Test
    void duplicateQualifiedTypesAreNotSilentlySelected() throws Exception {
        write("One.java", "package same; class Duplicate { void one() {} }");
        write("Two.java", "package same; class Duplicate { void two() {} }");
        var result = index();
        assertThat(result.symbols()).noneMatch(s -> s.kind().equals("CLASS") || s.kind().equals("METHOD"));
        assertThat(result.errors()).hasSize(2).allMatch(e -> e.code().equals("JAVA_DUPLICATE_TYPE"));
    }

    @Test
    void conflictingExplicitImportsDoNotProduceConfirmedCalls() throws Exception {
        write("One.java", "package one; class Thing { void call() {} }");
        write("Two.java", "package two; class Thing { void call() {} }");
        write("Use.java", "import one.Thing; import two.Thing; class Use { void use(Thing value) { value.call(); } }");
        assertThat(index().relationships()).filteredOn(e -> e.type().equals("CALLS")).singleElement()
                .extracting(Relationship::resolutionState).isEqualTo("UNRESOLVED");
    }

    @Test
    void ambiguousWildcardImportsDoNotSelectTheFirstCandidate() throws Exception {
        write("One.java", "package one; public class Thing { public void call() {} }");
        write("Two.java", "package two; public class Thing { public void call() {} }");
        write("Use.java", "import one.*; import two.*; class Use { void use(Thing value) { value.call(); } }");
        assertThat(index().relationships()).filteredOn(e -> e.type().equals("CALLS")).singleElement()
                .extracting(Relationship::resolutionState).isEqualTo("UNRESOLVED");
    }

    @Test
    void unrelatedNullOverloadsAreNotArbitrarilyResolved() throws Exception {
        write("Ambiguous.java", "class Ambiguous { void choose(String a) {} void choose(Integer a) {} void use() { choose(null); } }");
        assertThat(index().relationships()).filteredOn(e -> e.type().equals("CALLS")).singleElement()
                .extracting(Relationship::resolutionState).isEqualTo("UNRESOLVED");
    }

    @Test
    void genericOverridesAndStaticHidingAreDistinguished() throws Exception {
        write("Base.java", "class Base<T> { T run(T a) { return a; } static void hidden() {} private void own() {} }");
        write("Child.java", "class Child extends Base<String> { @Override String run(String a) { return a; } static void hidden() {} void own() {} }");
        var result = index();
        assertEdge(result, "java:method:Child#run(java.lang.String)", "java:method:Base#run(java.lang.Object)", "OVERRIDES");
        assertThat(result.relationships()).filteredOn(e -> e.type().equals("OVERRIDES")).hasSize(1);
    }

    @Test
    void invalidOverridesAndTypeAnnotationValuesAreNotReportedAsFactsOrSnippets() throws Exception {
        write("Base.java", "class Base { final void fixed() {} int value() { return 0; } }");
        write("Child.java", "class Child extends Base { @Override void fixed() {} @Override String value() { return null; } @Secret(\"fixture-sensitive\") Missing field; }");
        var result = index();
        assertThat(result.relationships()).filteredOn(e -> e.type().equals("OVERRIDES"))
                .allMatch(e -> !e.resolutionState().equals("RESOLVED"));
        assertThat(result.toString()).doesNotContain("fixture-sensitive");
    }

    private void assertEdge(Index result, String source, String target, String type) {
        assertThat(result.relationships()).anySatisfy(edge -> {
            assertThat(edge.sourceId()).isEqualTo(source);
            assertThat(edge.targetId()).isEqualTo(target);
            assertThat(edge.type()).isEqualTo(type);
            assertThat(edge.resolutionState()).isEqualTo("RESOLVED");
        });
    }
}
