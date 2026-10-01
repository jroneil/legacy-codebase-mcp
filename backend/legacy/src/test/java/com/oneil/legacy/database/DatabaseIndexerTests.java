package com.oneil.legacy.database;

import static org.assertj.core.api.Assertions.*;
import com.oneil.legacy.framework.FrameworkIndexer;
import com.oneil.legacy.scan.RepositoryInventory;
import com.oneil.legacy.symbol.JavaIndexModel.*;
import com.oneil.legacy.symbol.JavaSymbolIndexer;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class DatabaseIndexerTests {
    @TempDir Path root;
    @BeforeEach void fixture() throws Exception { copyFixture(root); }
    static void copyFixture(Path root) throws Exception {
        Path source = Path.of(DatabaseIndexerTests.class.getResource("/fixtures/database-usage").toURI());
        try (var files = Files.walk(source)) {
            for (var file : files.toList()) {
                var destination = root.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file)) Files.createDirectories(destination); else Files.copy(file, destination);
            }
        }
    }
    Index index() throws Exception {
        var inventory = new RepositoryInventory().collect(root);
        var javaIndex = new JavaSymbolIndexer().index(root, inventory);
        return new DatabaseIndexer().index(root, inventory, new FrameworkIndexer().index(root, inventory, javaIndex));
    }
    static Stream<Arguments> statements() {
        return Stream.of(
            Arguments.of("SELECT * FROM CUSTOMER", Set.of("CUSTOMER"), Set.of()),
            Arguments.of("INSERT INTO CUSTOMER(id) VALUES (?)", Set.of(), Set.of("CUSTOMER")),
            Arguments.of("UPDATE CUSTOMER SET name=? WHERE id=?", Set.of(), Set.of("CUSTOMER")),
            Arguments.of("DELETE FROM CUSTOMER WHERE id=?", Set.of(), Set.of("CUSTOMER")),
            Arguments.of("MERGE INTO CUSTOMER c USING CUSTOMER_STAGE s ON(c.id=s.id) WHEN MATCHED THEN UPDATE SET c.name=s.name", Set.of("CUSTOMER_STAGE"), Set.of("CUSTOMER")),
            Arguments.of("INSERT INTO ARCHIVE SELECT * FROM CUSTOMER", Set.of("CUSTOMER"), Set.of("ARCHIVE")),
            Arguments.of("INSERT INTO CUSTOMER SELECT * FROM CUSTOMER", Set.of("CUSTOMER"), Set.of("CUSTOMER")),
            Arguments.of("SELECT c.id FROM CUSTOMER c JOIN ADDRESS a ON c.id=a.customer_id", Set.of("CUSTOMER", "ADDRESS"), Set.of()),
            Arguments.of("SELECT * FROM CUSTOMER FOR UPDATE", Set.of("CUSTOMER"), Set.of()),
            Arguments.of("WITH tmp AS (SELECT * FROM CUSTOMER) SELECT * FROM tmp", Set.of("CUSTOMER"), Set.of()),
            Arguments.of("WITH tmp AS (SELECT * FROM CUSTOMER) SELECT * FROM TMP", Set.of("CUSTOMER"), Set.of()),
            Arguments.of("WITH customer AS (SELECT * FROM CUSTOMER) SELECT * FROM customer", Set.of("CUSTOMER"), Set.of()),
            Arguments.of("SELECT * FROM TMP WHERE EXISTS (WITH tmp AS (SELECT * FROM CUSTOMER) SELECT * FROM tmp)", Set.of("CUSTOMER", "TMP"), Set.of()),
            Arguments.of("WITH RECURSIVE tmp AS (SELECT id FROM CUSTOMER UNION ALL SELECT id FROM tmp) SELECT * FROM tmp", Set.of("CUSTOMER"), Set.of()),
            Arguments.of("SELECT c.id FROM CUSTOMER c FOR UPDATE OF c.id", Set.of("CUSTOMER"), Set.of()),
            Arguments.of("UPDATE CUSTOMER SET name=(SELECT name FROM STAGING)", Set.of("STAGING"), Set.of("CUSTOMER")),
            Arguments.of("SELECT * FROM app.\"Customer\"", Set.of("app.\"Customer\""), Set.of()),
            Arguments.of("SELECT c.id FROM CUSTOMER c, ADDRESS a WHERE c.id=a.customer_id(+)", Set.of("CUSTOMER", "ADDRESS"), Set.of())
        );
    }
    @ParameterizedTest @MethodSource("statements")
    void classifiesSqlWithoutTreatingAliasesOrCtesAsTables(String sql, Set<String> reads, Set<String> writes) {
        var result = new SqlAnalyzer().analyze(sql);
        assertThat(result.diagnostic()).isNull();
        assertThat(result.reads()).isEqualTo(reads); assertThat(result.writes()).isEqualTo(writes);
    }
    @Test void jdbcPreparedTemplateConstantsAndNativeQueriesHaveDirectEvidence() throws Exception {
        var index = index();
        for (String name : List.of("prepared", "insert", "update", "delete", "merge", "copy", "lock", "oracle", "nativeQuery")) {
            var executed = index.relationships().stream().filter(e -> e.sourceId().contains("#" + name + "(") && e.type().equals("EXECUTES_QUERY")).toList();
            assertThat(executed).as(name).hasSize(1);
            assertThat(index.relationships()).anyMatch(e -> e.sourceId().equals(executed.getFirst().targetId()) && (e.type().equals("READS_TABLE") || e.type().equals("WRITES_TABLE")));
        }
        assertThat(index.relationships()).anyMatch(e -> e.sourceId().equals("java:field:demo.SqlConstants#FIND") && e.type().equals("DECLARES_QUERY"));
        assertThat(index.relationships()).filteredOn(e -> e.evidenceType().equals("JSQLPARSER")).allSatisfy(e -> {
            assertThat(e.line()).isPositive(); assertThat(e.column()).isPositive(); assertThat(e.resolutionState()).isEqualTo("RESOLVED");
        });
        assertThat(index.toString()).doesNotContain("fixture-secret-must-not-escape");
        assertThat(index.symbols()).filteredOn(s -> s.kind().equals("QUERY_ARTIFACT")).anyMatch(s -> s.signature().contains("SqlConstants.FIND"));
    }
    @Test void dynamicMalformedAndVendorSqlRemainInspectableWithoutInventedTables() throws Exception {
        var index = index();
        for (String name : List.of("dynamic", "malformed", "vendor")) {
            String id = index.relationships().stream().filter(e -> e.sourceId().contains("#" + name + "(") && e.type().equals("EXECUTES_QUERY")).findFirst().orElseThrow().targetId();
            assertThat(index.symbols()).filteredOn(s -> s.stableId().equals(id)).singleElement().satisfies(s -> assertThat(s.resolutionState()).isEqualTo("UNRESOLVED"));
            assertThat(index.relationships()).noneMatch(e -> e.sourceId().equals(id) && e.type().endsWith("_TABLE"));
        }
        assertThat(index.errors()).extracting(e -> e.code()).contains("SQL_DYNAMIC", "SQL_PARSE", "HIBERNATE_XML_PARSE");
        assertThat(index.symbols()).anyMatch(s -> s.kind().equals("QUERY_ARTIFACT") && s.signature().contains("<dynamic>") && s.signature().contains("tableName"));
        assertThat(index.symbols()).noneMatch(s -> s.stableId().equals("db:table:tableName"));
    }
    @Test void hibernateMappingsHqlAndNamedQueryStayDistinctFromSqlTables() throws Exception {
        var index = index();
        assertThat(index.relationships()).anyMatch(e -> e.sourceId().equals("java:type:demo.Customer") && "db:table:CUSTOMER".equals(e.targetId()) && e.type().equals("MAPS_TO_TABLE"));
        assertThat(index.symbols()).filteredOn(s -> s.kind().equals("QUERY_ARTIFACT") && s.simpleName().equals("Customer.find"))
                .singleElement().satisfies(s -> assertThat(s.signature()).contains("HQL", "HQL_INFERRED"));
        assertThat(index.relationships()).anyMatch(e -> e.type().equals("READS_TABLE") && e.resolutionState().equals("INFERRED") && e.evidenceType().equals("HQL_MAPPING_HEURISTIC"));
        assertThat(index.relationships()).anyMatch(e -> e.sourceId().contains("#named(") && e.type().equals("EXECUTES_QUERY") && e.targetId().startsWith("db:query:Customer.hbm.xml"));
        assertThat(index.symbols()).noneMatch(s -> s.stableId().equals("db:table:Customer"));
    }
    @Test void procedureReferencesDoNotInventProcedureBodyTables() throws Exception {
        var index = index();
        String id = index.relationships().stream().filter(e -> e.sourceId().contains("#procedure(") && e.type().equals("EXECUTES_QUERY")).findFirst().orElseThrow().targetId();
        assertThat(index.symbols()).filteredOn(s -> s.stableId().equals(id)).singleElement().satisfies(s -> assertThat(s.signature()).contains("CRM.REFRESH_CUSTOMER", "CALL"));
        assertThat(index.relationships()).noneMatch(e -> e.sourceId().equals(id) && e.type().endsWith("_TABLE"));
    }
    @Test void mutableAndShadowedVariablesCannotResolveToStaleSql() throws Exception {
        Files.writeString(root.resolve("src/demo/NegativeDAO.java"), """
            package demo;
            class NegativeDAO {
              static final String SQL = "SELECT * FROM WRONG";
              void run(java.sql.Statement st, String SQL) throws Exception { st.execute(SQL); }
              void changed(java.sql.Connection connection, String dynamic) throws Exception {
                String sql = "SELECT * FROM ORIGINAL";
                sql = dynamic;
                connection.prepareStatement(sql).executeQuery();
              }
            }
            """);
        var index = index();
        for (String method : List.of("run", "changed")) {
            var execution = index.relationships().stream().filter(e -> e.sourceId().startsWith("java:method:demo.NegativeDAO#" + method + "(") && e.type().equals("EXECUTES_QUERY")).findFirst().orElseThrow();
            assertThat(index.symbols()).filteredOn(s -> s.stableId().equals(execution.targetId())).singleElement().satisfies(s -> assertThat(s.resolutionState()).isEqualTo("UNRESOLVED"));
        }
    }
    @Test void duplicateNamedQueriesAndEntityMappingsNeverChooseOne() throws Exception {
        Files.writeString(root.resolve("Other.hbm.xml"), "<hibernate-mapping><class name='demo.Customer' table='OTHER_CUSTOMER'/><query name='Customer.find'>from Customer</query></hibernate-mapping>");
        var index = index();
        assertThat(index.errors()).extracting(e -> e.code()).contains("HQL_ENTITY_AMBIGUOUS_OR_MISSING", "NAMED_QUERY_AMBIGUOUS_OR_MISSING");
        assertThat(index.relationships()).filteredOn(e -> e.sourceId().contains("#named(") && e.type().equals("DECLARES_QUERY") && e.targetId() == null)
                .singleElement().satisfies(e -> assertThat(e.targetDescription()).contains("count=2"));
    }
    @Test void unsupportedHqlDoesNotBecomeSqlAndOrdinaryMethodsAreNotDatabaseApis() throws Exception {
        Files.writeString(root.resolve("src/demo/Other.java"), """
            package demo;
            class Other {
              void go(org.hibernate.Session session, Other other) {
                session.createQuery("from Missing m join m.children c").list();
                other.query("SELECT * FROM FALSE_POSITIVE");
              }
              void query(String ignored) {}
            }
            """);
        var index = index();
        assertThat(index.errors()).anyMatch(e -> e.code().equals("HQL_UNSUPPORTED"));
        assertThat(index.symbols()).noneMatch(s -> s.stableId().equals("db:table:FALSE_POSITIVE") || s.stableId().equals("db:table:Missing"));
    }
    @Test void fullIndexIsStableAndPreservesAllEarlierEvidenceAndReadOnlyInput() throws Exception {
        var inventory = new RepositoryInventory().collect(root);
        var existing = new FrameworkIndexer().index(root, inventory, new JavaSymbolIndexer().index(root, inventory));
        var first = new DatabaseIndexer().index(root, inventory, existing);
        assertThat(new DatabaseIndexer().index(root, inventory, existing)).isEqualTo(first);
        assertThat(first.symbols()).containsAll(existing.symbols()); assertThat(first.relationships()).containsAll(existing.relationships());
        assertThat(new RepositoryInventory().collect(root).files()).isEqualTo(inventory.files());
    }
    @Test void secureRereadsAndXmlEntitiesCannotSupplyDatabaseEvidence() throws Exception {
        var inventory = new RepositoryInventory().collect(root);
        Files.writeString(root.resolve("Customer.hbm.xml"), "<hibernate-mapping/>");
        var changed = new DatabaseIndexer().index(root, inventory, Index.empty());
        assertThat(changed.errors()).anyMatch(e -> e.code().equals("HIBERNATE_XML_PARSE"));
        Files.writeString(root.resolve("Customer.hbm.xml"), "<!DOCTYPE hibernate-mapping [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]><hibernate-mapping>&secret;</hibernate-mapping>");
        assertThat(index().errors()).anyMatch(e -> e.code().equals("HIBERNATE_XML_PARSE"));
        Files.delete(root.resolve("Customer.hbm.xml"));
        Files.createSymbolicLink(root.resolve("Customer.hbm.xml"), root.resolve("Broken.hbm.xml"));
        assertThat(new DatabaseIndexer().index(root, inventory, Index.empty()).errors()).anyMatch(e -> e.code().equals("HIBERNATE_XML_PARSE"));
    }
    @Test void unsupportedMultiStatementAndWriteExtensionsAreNotMisreportedAsReadOnly() {
        for (String sql : List.of("SELECT * FROM A; DELETE FROM B", "SELECT * INTO ARCHIVE FROM CUSTOMER",
                "UPDATE c SET name='x' FROM CUSTOMER c", "EXECUTE @procedureName")) {
            var result = new SqlAnalyzer().analyze(sql);
            assertThat(result.supported()).as(sql).isFalse();
            assertThat(result.reads()).isEmpty(); assertThat(result.writes()).isEmpty();
        }
    }
    @Test void dynamicHibernateTableNamesAreNotMaterialized() throws Exception {
        Files.writeString(root.resolve("Dynamic.hbm.xml"), "<hibernate-mapping><class name='demo.Customer' table='${tableName}'/></hibernate-mapping>");
        var index = index();
        assertThat(index.errors()).anyMatch(e -> e.code().equals("HIBERNATE_TABLE_UNRESOLVED"));
        assertThat(index.symbols()).noneMatch(s -> s.kind().equals("DATABASE_TABLE") && s.stableId().contains("${"));
    }
    @Test void constantEvaluationRespectsLexicalScopeAndStaticImports() throws Exception {
        Files.writeString(root.resolve("src/demo/ScopedDAO.java"), """
            package demo;
            import static demo.SqlConstants.FIND;
            class ScopedDAO {
              static final String SQL = "SELECT * FROM OUTER_TABLE";
              void run(java.sql.Statement statement) throws Exception {
                for (String SQL = "SELECT * FROM INNER_TABLE"; false;) {}
                statement.executeQuery(SQL);
                statement.executeQuery(FIND);
              }
            }
            """);
        var index = index();
        var executed = index.relationships().stream().filter(e -> e.sourceId().contains("ScopedDAO#run(") && e.type().equals("EXECUTES_QUERY")).map(Relationship::targetId).toList();
        assertThat(executed).hasSize(2);
        assertThat(index.relationships()).filteredOn(e -> executed.contains(e.sourceId()) && e.type().equals("READS_TABLE"))
                .extracting(Relationship::targetId).contains("db:table:OUTER_TABLE", "db:table:CUSTOMER").doesNotContain("db:table:INNER_TABLE");
    }
    @Test void oracleDollarQuotedAndCommentSecretsAreRemovedFromArtifacts() throws Exception {
        assertThat(DatabaseModel.safeSql("SELECT q'[oracle's-secret]', $tag$dollar-secret$tag$, \"double-secret\" FROM T"))
                .doesNotContain("oracle", "secret");
        assertThat(DatabaseModel.safeSql("SELECT '" + "secret".repeat(12000))).isEqualTo("SELECT '?'");
        Files.writeString(root.resolve("src/demo/SecretDAO.java"), """
            package demo;
            class SecretDAO { void run(java.sql.Statement st) throws Exception {
                st.executeQuery(/* comment-secret */ "SELECT * FROM CUSTOMER WHERE password='literal-secret'");
            } }
            """);
        assertThat(index().toString()).doesNotContain("comment-secret", "literal-secret");
    }
    @Test void redactsValuesCommentsAndPartialQuotedSecretsAndBoundsMetadata() {
        assertThat(DatabaseModel.safeSql("SELECT * FROM T WHERE password='fixture-secret' -- token-secret\n/* hidden-secret */"))
                .doesNotContain("fixture-secret", "token-secret", "hidden-secret");
        assertThat(DatabaseModel.safeSql("SELECT * FROM T WHERE token='partial-secret<dynamic>"))
                .doesNotContain("partial-secret");
        assertThat(new SqlAnalyzer().analyze("SELECT " + "x".repeat(65536)).diagnostic()).isEqualTo("SQL_LIMIT");
    }
}
