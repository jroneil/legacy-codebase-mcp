# Slice 8 — Grails Mapping validation

Date: 2026-10-01. Base commit: `783c14e`; validated the uncommitted working tree,
which also contains the prior Slice 3–7 changes. No commit made.

## Grails baseline/version

- Supported layout: **Grails 3.x–6.x** — `grails-app/controllers`, `grails-app/services`,
  `grails-app/domain`, `grails-app/conf/spring/resources.groovy`, and
  `UrlMappings.groovy` under `grails-app/`. The Grails 2.x `UrlMappings.groovy`
  location (`grails-app/conf/UrlMappings.groovy`) is recognized by the same
  filename rule. Groovy 2.4–3.0 source syntax is parsed.
- Groovy parser: **Groovy 5.0.8** (`org.apache.groovy:groovy`, the version Spring Boot
  4.1.1 manages), used through `SourceUnit.parse()/completePhase()/nextPhase()/convert()`,
  i.e. the **conversion phase only**. Target code is never compiled, loaded or executed;
  no Spring/Grails context is started.
- This is **not** generic Grails support. Constructs outside the recognized layout and
  the documented conventions below remain unindexed or `UNRESOLVED`.

## Files changed

Added (main): `backend/legacy/src/main/java/com/oneil/legacy/grails/GrailsIndexer.java`

Added (tests/fixtures): `backend/legacy/src/test/java/com/oneil/legacy/grails/GrailsIndexerTests.java`,
`.../grails/GrailsIntegrationTests.java`, and the fixture repository
`backend/legacy/src/test/resources/fixtures/grails/` (14 Groovy files):

```text
grails-app/controllers/demo/UrlMappings.groovy
grails-app/controllers/demo/CustomerController.groovy
grails-app/controllers/demo/BookController.groovy
grails-app/controllers/demo/AmbiguousController.groovy
grails-app/controllers/demo/LegacyController.groovy
grails-app/controllers/demo/SneakyController.groovy
grails-app/controllers/demo/Broken.groovy
grails-app/services/demo/CustomerService.groovy
grails-app/services/demo/AmbiguousService.groovy
grails-app/services/demo/other/AmbiguousService.groovy
grails-app/domain/demo/Customer.groovy
grails-app/domain/demo/CustomerOrder.groovy
grails-app/conf/spring/resources.groovy
src/groovy/demo/CustomerDao.groovy
```

Modified:

- `backend/legacy/pom.xml` — Groovy dependency
- `backend/legacy/src/main/java/com/oneil/legacy/scan/ScanService.java` — Grails stage in the scan chain
- `backend/legacy/src/main/resources/application.properties` — default analyzer version
- `backend/legacy/src/test/java/com/oneil/legacy/{symbol/SymbolIntegrationTests,scan/ScanIntegrationTests,framework/FrameworkIntegrationTests,database/DatabaseIntegrationTests}.java` — `ScanService` constructor arity
- `frontend/legacy-ui/lib/view.ts`, `frontend/legacy-ui/lib/view.test.ts` — display labels for the new `groovy:`/`grails:` stable-ID prefixes
- `README.md` — Grails section and analyzer-version references

No schema change and **no new symbol kind or relationship type**: controllers/services/domains
are `CLASS`, actions are `METHOD`, mappings are `ROUTE`, resources.groovy beans are `BEAN`
under a `CONTEXT`, and the existing `CONTAINS`, `CALLS`, `ROUTES_TO`, `INJECTS`, `WIRES_TO`,
`READS_TABLE` and `MAPS_TO_TABLE` types carry the evidence. Flyway remains at **V4**.

## Dependencies added

| Dependency | Resolved version | Scope |
| --- | --- | --- |
| `org.apache.groovy:groovy` | 5.0.8 (managed by `spring-boot-dependencies` 4.1.1) | compile |

No test-only dependency was added.

## Commands/test counts

Backend commands ran in `backend/legacy`.

| Command | Result |
| --- | --- |
| `./mvnw dependency:tree` | `org.apache.groovy:groovy:jar:5.0.8:compile` |
| `./mvnw -DskipTests compile` | Passed |
| `./mvnw -Dtest=GrailsIndexerTests test` | 10 passed (0 failures/errors/skips) |
| `./mvnw -Dtest=GrailsIntegrationTests test` | 5 passed (PostgreSQL 17.11 Testcontainers + REST + MCP) |
| `./mvnw test` | Run once at completion: **160 passed**, 0 failures/errors/skips, BUILD SUCCESS (145 before Slice 8) |
| `cd frontend/legacy-ui && npm test` | 47 passed (label change only) |
| `cd frontend/legacy-ui && npm run build` | Passed: TypeScript and static generation |

Logs: `/tmp/legacy-slice8-final-backend.log`, `/tmp/legacy-slice8-final-frontend.log`.
Migration, MCP transport, Compose, packaged-JAR and parser smoke runs were not repeated;
Slice 8 adds no infrastructure. The frontend was re-validated only because the stable-ID
label helper changed.

## Acceptance evidence

- **URL mappings.** `/customer/$id` maps to `groovy:method:demo.CustomerController#show(Long)`
  with `ROUTES_TO`/`RESOLVED`; `/customer` resolves the Grails default action `index` as
  `INFERRED`; `/books` (`resources:`) resolves the controller class as `INFERRED`; the nested
  `group("/api") { … }` mapping is walked; `controller: "$controller"` stays `UNRESOLVED`.
- **Controllers, actions, services.** `CLASS`/`METHOD` symbols with `groovy:` stable IDs,
  positive line/column evidence and `GROOVY` evidence type; `show`, `index`, `ping` and the
  service methods are all indexed.
- **Injection.** Declared type (`CustomerService explicitService`) is `RESOLVED`; the untyped
  `def customerService` naming convention is `INFERRED`; two `AmbiguousService` classes keep an
  `UNRESOLVED` `INJECTS` edge listing both candidates; a conventional `missingService` with no
  indexed class stays `UNRESOLVED`.
- **Service usage.** `CustomerController.show` → `CALLS` → `CustomerService.findByLastName(String)`
  as `RESOLVED`, with the call site's evidence location.
- **Domain and table mapping.** `Customer` → `db:table:CUSTOMER` via explicit
  `static mapping = { table 'CUSTOMER' }` (`MAPS_TO_TABLE`, `RESOLVED`); `CustomerOrder` →
  `db:table:customer_order` by the Grails/Hibernate physical naming convention (`INFERRED`).
- **Dynamic finder.** `Customer.findByLastName(name)` on a statically determined domain produces
  `READS_TABLE` → `db:table:CUSTOMER` (`INFERRED`) and `CALLS` → `groovy:type:demo.Customer`.
  Arbitrary method names on unknown receivers produce no table evidence.
- **resources.groovy.** `BEAN` symbols with `WIRES_TO` to the bean class, `INJECTS` for
  `ref('customerDao')` (`RESOLVED`) and `ref('sessionFactory')` (`UNRESOLVED`, the bean is not
  defined in this file).
- **Metaprogramming and malformed source.** `Customer.metaClass…` and a `methodMissing`
  declaration record a localized `GROOVY`/`GRAILS_METAPROGRAMMING` analysis error plus an
  `UNRESOLVED` `CALLS` edge; a syntactically broken file records `GROOVY_PARSE` while every other
  file is indexed. An unstructured `resources.groovy` records `GRAILS_RESOURCES_UNSUPPORTED`.
- **End-to-end flow** (integration test, PostgreSQL): querying the route
  `/customer/$id` returns the path
  `grails:route:…#/customer/$id → CustomerController.show(Long) → CustomerService.findByLastName(String) → db:table:CUSTOMER`
  and a second path through `groovy:type:demo.Customer`; every path that reaches the table is
  `INFERRED` (weakest edge), and intermediate prefixes stay `RESOLVED`.
- **Table impact.** `/api/relationships/database-tables` reports `CUSTOMER` with `READ` (direct
  from the finder) and `MAPPING` (through the domain) as distinct results, each with its full
  evidence path. An ambiguous injection invents no table impact.
- **REST/MCP equivalence.** The same `TraversalQueries` result is returned through
  `/api/relationships/trace` and through the MCP `trace_component` tool for the Grails route;
  `/api/entry-points` and `/api/symbols/detail` expose the Grails route and controller.
- **Snapshots and identity.** Two consecutive scans produce identical Grails symbol/relationship
  evidence, the previous snapshot is retained unchanged, and the active pointer moves to the new
  completed scan.
- **Struts baseline unchanged.** The Struts/Spring fixture still indexes with no `groovy:`/`grails:`
  symbols, its route still resolves `ROUTES_TO`/`RESOLVED`, and `/api/entry-points` plus
  `/api/relationships/trace` behave as before. `FrameworkIndexer` was not modified.

## Resolution/inference rules used

| State | Basis |
| --- | --- |
| `RESOLVED` | Explicit source/configuration evidence: `controller:`/`action:` values in a mapping, a declared property/field type, `static mapping = { table 'X' }`, an explicit bean class in `resources.groovy`, a resolvable `ref`/bean name |
| `INFERRED` | Documented Grails/GORM convention with a clear basis: default `index` action, `resources:` REST mapping to the controller class, untyped `def xService` → `XService`, conventional snake_case table name, GORM dynamic-finder read, `CALLS` to a domain receiver |
| `UNRESOLVED` | Dynamic or metaprogrammed behavior: `"$controller"`/`"$action"` expressions, unknown or ambiguous controller/service/action, missing dependency, unresolved bean reference, `metaClass`/`methodMissing`/`propertyMissing` |

Ambiguity always returns a bounded candidate list (`… ; candidates=[…]; count=N`) and never
selects one silently. Path confidence remains the weakest edge, so any path through a GORM
convention or a conventional table name is reported `INFERRED`.

## Known limitations

- Static Groovy AST only. No type resolution, no semantic analysis, no compilation, no execution:
  method resolution uses name plus arity (and the receiver's index, not the runtime class).
- Layout-scoped: Grails-specific edges are produced only for the recognized `grails-app/…`
  paths. Groovy files elsewhere still yield `CLASS`/`METHOD` symbols but no framework edges.
- Not supported: `static mapping = { table: 'X' }` written as a labelled statement (Groovy parses
  it as a label), `@Entity`-annotated domains outside `grails-app/domain` are recognized but
  their table naming still follows the convention, `hasMany`/`belongsTo` domain-to-domain
  relationships, GORM write finders (`save`/`delete`) and `Where`/criteria queries, controller
  `beforeInterceptor`/`afterInterceptor`, GSP view rendering, Grails 2.x `grails-app/conf/*`
  layouts other than `UrlMappings.groovy`, plugins, and build-tool config.
- `resources.groovy` support covers `beanName(Class) { property = ref('x') }` and bare bean-name
  references. Inline `bean(...)`, factory methods, closures-as-values and alias/namespace forms
  are not interpreted.
- The entry-point trace endpoint (`/api/entry-points/trace`) keeps its Struts/Spring bean-bridge
  semantics and stops at the controller action for a Grails route; the full service/domain/table
  chain is returned by `/api/relationships/trace` and `/api/relationships/database-tables`.
  `FrameworkIndexer`/`FrameworkQueries` were deliberately not generalized.
- Table identities preserve the declared spelling, so an explicit `'CUSTOMER'` and a conventional
  `customer_order` can coexist in one snapshot; no case folding or live catalogue is consulted.
- Bounds: 5,000 Groovy files per scan, 20,000 visited AST nodes per method; exceeded limits record
  `GROOVY_FILE_LIMIT` or stop that method's walk. Parse failures, unknown encodings and unsupported
  resources.groovy shapes record localized `GROOVY` errors and never fail an otherwise valid scan.
- One regression was found and fixed during validation: the new indexer initially collapsed
  duplicate symbol identities arriving from earlier stages, which silently turned a failing
  publication into a successful one. `GrailsIndexer` now rejects duplicate identities exactly like
  `FrameworkIndexer`, and `SymbolIntegrationTests.failedSymbolPublicationRollsBackAndKeepsPriorAnalysis`
  passes again.

No unresolved Slice 8 acceptance blocker remains.
