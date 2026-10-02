# Slice 12 — Spring Boot / Annotation Wiring validation

Date: 2026-10-02. Base commit: `bdc98ed`. Changes are uncommitted and include the
uncommitted Slice 11 baseline.

## Files changed

Slice 12 adds or changes:

- `README.md`
- `backend/legacy/src/main/java/com/oneil/legacy/framework/{FrameworkIndexer,SpringAnnotationIndexer}.java`
- `backend/legacy/src/test/java/com/oneil/legacy/framework/{SpringAnnotationIndexerTests,SpringBootIntegrationTests}.java`
- `backend/legacy/src/test/resources/fixtures/spring-boot/**`
- `frontend/legacy-ui/components/{RelationshipList,TableImpactList,TraceView}.tsx`
- `frontend/legacy-ui/components/components.test.tsx`
- `frontend/legacy-ui/lib/{view,view.test}.ts`
- `docs/validation/SLICE_12_VALIDATION.md`

No dependency, schema, migration, REST endpoint, MCP tool, or traversal rule changed.

## Commands and results

| Command | Result |
| --- | --- |
| `cd backend/legacy && ./mvnw -DskipTests compile` | Build success |
| `cd backend/legacy && ./mvnw -Dtest=SpringAnnotationIndexerTests test` | 7 passed, 0 failed |
| `cd backend/legacy && ./mvnw -Dtest=SpringAnnotationIndexerTests,SpringBootIntegrationTests test` | 9 passed, 0 failed |
| `cd backend/legacy && ./mvnw -Dtest=SpringAnnotationIndexerTests,SpringBootIntegrationTests,SpringMvcIndexerTests,SpringMvcIntegrationTests,FrameworkIndexerTests,FrameworkIntegrationTests,GrailsIndexerTests,GrailsIntegrationTests,Grails26IndexerTests,Grails26IntegrationTests,DatabaseIndexerTests,DatabaseIntegrationTests,McpRestEquivalenceTests,MountedRepositoryServiceTests,ScanIntegrationTests test` | 126 passed, 0 failed |
| `cd backend/legacy && ./mvnw test` | 194 passed, 0 failed; build success |
| `docker run --rm -v .../frontend/legacy-ui:/app -w /app node:24-bookworm-slim sh -lc 'npm test && npm run build'` | 52 passed, 0 failed; Next.js production build passed |

## Supported baseline and rules

- Source-only analysis recognizes `@SpringBootApplication`, `@Configuration`,
  `@Component`, `@Service`, `@Repository`, `@Controller`, and `@RestController` without
  starting Spring or loading target classes.
- Component classes reuse Java type symbols. Deterministic `BEAN` symbols retain bean
  names and type evidence. Explicit names are `RESOLVED`; conventional decapitalized
  names are `INFERRED`; dynamic names remain `UNRESOLVED`.
- `@Bean` methods create deterministic beans with their factory method, static return
  type, name/aliases, and source location. Method bodies are not executed.
- The application package is an inferred default scan boundary. Literal, array, and
  bounded `static final String` `scanBasePackages`/`@ComponentScan` values add roots.
  Components outside known roots remain unresolved evidence and are excluded from
  injection candidates.
- A sole constructor and annotated constructors, fields, and setters support
  `@Autowired`, javax/jakarta `@Inject`, and javax/jakarta `@Resource`. Unique compatible
  candidates create `INJECTS`; explicit resource names select by bean name. Ambiguous,
  missing, and dynamic targets remain `UNRESOLVED`, with candidates and diagnostics.
- Spring Data interfaces extending `Repository`, `CrudRepository`,
  `PagingAndSortingRepository`, or `JpaRepository` connect to a statically known generic
  entity. An explicit `@Table(name=...)` creates resolved `MAPS_TO_TABLE` evidence.
  Declared `find`/`read`/`get` methods create inferred reads and declared
  `save`/`delete`/`remove` methods create inferred writes only when that table is known.

## Acceptance evidence

The completed PostgreSQL-backed scan proves this shared evidence chain:

```text
GET /customers/{id}
  -> CustomerController.get(Long)
  -> CustomerService.find(Long)
  -> CustomerRepository.findById(Long)
  -> Customer
  -> MAPS_TO_TABLE CUSTOMER
```

The explicit mapping edge is `RESOLVED`; separate Spring Data read evidence is
`INFERRED`, and path confidence follows the weakest edge. The POST fixture proves:

```text
POST /customers
  -> CustomerController.create(Customer)
  -> CustomerService.save(Customer)
  -> CustomerRepository.save(Customer)
  -> WRITES_TABLE CUSTOMER · INFERRED
```

Both paths retain source locations. REST and MCP return equivalent trace and entry-point
evidence through the existing services and tools. The UI now labels Spring MVC, Spring
Component, Spring Bean, Spring Data, and JPA evidence in relationship lists, traces, and
table-impact paths; unknown evidence labels retain their stored value.

Focused tests also cover default/explicit/alias/dynamic bean names; configuration and
factory beans; constructor, field, and setter injection; unique, ambiguous, and missing
candidates; explicit and default component-scan roots; outside-boundary components;
Spring Data generic entities and derived operations; malformed Java localization;
deterministic IDs; source evidence; immutable scan publication; and REST/MCP equivalence.
The 126-test regression covers Struts 1, Spring XML, Spring MVC, Grails 2.6 and modern
fixtures, JDBC/Hibernate database analysis, local mounted-repository scanning, scan
snapshot behavior, and REST/MCP equivalence.

## Known limitations

- Runtime profiles, conditions, qualifiers, `@Primary`, ordering, proxying, custom
  composed annotations, and the complete BeanFactory/component-scanning algorithm are
  not simulated.
- Component-scan class references and arbitrary annotation expressions are not resolved;
  unsupported or dynamic values remain `UNRESOLVED`.
- Spring Data support is limited to recognized base interfaces, a statically available
  generic entity, declared method-name prefixes, and an explicit JPA table mapping. It
  does not parse the full derived-query grammar or synthesize inherited repository
  methods.
- JPA conventional naming, inheritance, secondary tables, converters, and runtime
  naming strategies are not modeled.
- Configuration properties are inventoried as source files but property binding is not
  analyzed.

No Slice 12 acceptance blocker remains.
