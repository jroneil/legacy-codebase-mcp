# Slice 8.1 — Grails 2.6 Compatibility validation

Date: 2026-10-01. Base commit: `086fa9e` (`add Grails route, service, and domain analysis`);
validated the uncommitted Slice 8.1 working tree. No commit made.

## Grails 2.6 compatibility scope

- Supported baseline is now **Grails 2.6 plus representative Grails 3.x–6.x layouts**.
  Not generic Grails 2.x support.
- In scope for 2.6: `grails-app/conf/UrlMappings.groovy`; Grails 2.x **named URL
  mappings** (`name customerList: "/customers"(controller: "customer", action: "list")`);
  **nested per-mapping constraint closures** (`"/product/$id?"(…) { id matches: /\d+/ }`);
  status-code mappings without a controller (`"500"(view: "/error")`); controllers and
  actions; services and conventional service injection; `static transactional` /
  `static allowedMethods` / `static constraints` (static members, not dependencies);
  domain classes; Grails 2.x GORM mapping closures containing `id column:`/`generator:`/
  `version false` alongside `table 'X'`; conventional table naming; the existing GORM
  dynamic finders; and `grails-app/conf/spring/resources.groovy` with `import` and
  `ref(...)` wiring under the same rules as Slice 8.
- Parser: Groovy **5.0.8** parsed every Grails 2.6-era construct in the fixture
  (named-argument method calls, nested closures, closure properties, GString
  interpolation, `static` maps, `matches:` named arguments) — **no parser
  incompatibility was found**, so the parser was neither replaced nor extended.
- No schema change, no new symbol kind, no new relationship type, no dependency change.
  Flyway remains at **V4**.

## Files changed

Added:

- `backend/legacy/src/test/java/com/oneil/legacy/grails/Grails26IndexerTests.java`
- `backend/legacy/src/test/java/com/oneil/legacy/grails/Grails26IntegrationTests.java`
- `backend/legacy/src/test/resources/fixtures/grails26/` (12 Groovy files)

```text
grails-app/conf/UrlMappings.groovy
grails-app/conf/spring/resources.groovy
grails-app/controllers/demo/CustomerController.groovy
grails-app/controllers/demo/LegacyClosureController.groovy
grails-app/controllers/demo/LegacyAmbiguousController.groovy
grails-app/controllers/demo/BrokenLegacyController.groovy
grails-app/services/demo/CustomerService.groovy
grails-app/services/demo/AmbiguousService.groovy
grails-app/services/demo/legacy/AmbiguousService.groovy
grails-app/domain/demo/Customer.groovy
grails-app/domain/demo/CustomerOrder.groovy
src/groovy/demo/CustomerDao.groovy
```

- `docs/validation/SLICE_08_1_VALIDATION.md`

Modified:

- `backend/legacy/src/main/java/com/oneil/legacy/grails/GrailsIndexer.java` — three
  bounded compatibility changes, no redesign:
  1. Grails 2.x **named mappings** are unwrapped (`name x: "uri"(…)` is resolved as the
     mapping for `uri`) instead of producing a bogus `ROUTE` symbol whose URI was the
     literal text `name`; the mapping name is preserved as route evidence
     (`name=customerList; controller=…; action=…`).
  2. A route-shape guard skips DSL calls that cannot be route paths (a nested
     constraint entry such as `id matches: /\d+/` no longer becomes a `ROUTE`); route
     URIs must start with `/` or be a three-digit status code.
  3. A named mapping's nested closure is still walked for constraints.
- `README.md` — baseline wording updated to "Grails 2.6 plus representative Grails
  3.x–6.x layouts", legacy DSL boundaries documented, Slice 8.1 validation linked.

No frontend file changed, so frontend tests/build were not run.

## Commands/test counts

Backend commands ran in `backend/legacy`.

| Command | Result |
| --- | --- |
| `./mvnw -DskipTests compile` | Passed |
| `./mvnw -Dtest=Grails26IndexerTests test` | 7 passed, 0 failures/errors/skips |
| `./mvnw -Dtest=Grails26IntegrationTests test` | 3 passed (PostgreSQL 17.11 Testcontainers + REST + MCP) |
| `./mvnw test` | Run once at completion: **170 passed**, 0 failures/errors/skips, BUILD SUCCESS (160 before Slice 8.1) |

Log: `/tmp/legacy-slice81-final-backend.log`. MCP transport, Compose, packaged-JAR and
unrelated parser/database smoke runs were not repeated; Slice 8.1 changes no
infrastructure and no payload shape.

## Acceptance evidence

- **Grails 2.6 `UrlMappings.groovy`.** `grails-app/conf/UrlMappings.groovy` is indexed;
  `/customer/$id` → `groovy:method:demo.CustomerController#show(Long)`
  (`ROUTES_TO`/`RESOLVED`); `/customer` resolves the conventional default `index` as
  `INFERRED`; `"500"(view: …)` creates a `ROUTE` with no controller target; the dynamic
  `"/$controller/$action?/$id?"` mapping stays `UNRESOLVED`.
- **Named mapping.** `name customerList: "/customers"(controller:"customer", action:"list")`
  produces the route `grails:route:grails-app/conf/UrlMappings.groovy#/customers` with
  signature `name=customerList; controller=customer; action=list`, targeting
  `groovy:method:demo.CustomerController#list()` as `RESOLVED`. No `ROUTE` named `name`
  exists.
- **Constraint closure.** The nested `id matches: /\d+/` entry produces no `ROUTE`
  symbol, while the enclosing `/product/$id?` mapping is still indexed.
- **Controller/action, service injection, service call.** `static allowedMethods` is not
  a dependency; `def customerService` yields `INJECTS` → `demo.CustomerService`
  (`INFERRED`); `show` → `CALLS` → `CustomerService.findByLastName(String)`
  (`RESOLVED`); `list` → `CALLS` → `findAllCustomers()` (`RESOLVED`).
- **Domain and GORM.** `Customer` → `db:table:CUSTOMER` via `table 'CUSTOMER'` inside a
  2.x mapping closure that also contains `id column: 'customer_id', generator: 'identity'`
  and `version false` (`MAPS_TO_TABLE`/`RESOLVED`, the extra entries are ignored);
  `CustomerOrder` → `db:table:customer_order` (`INFERRED`).
- **Dynamic finders.** `Customer.findByLastName(lastName)` and `Customer.findAll()`
  produce `READS_TABLE` → `db:table:CUSTOMER` (`INFERRED`) plus `CALLS` to the domain
  (`INFERRED`), unchanged from Slice 8.
- **`resources.groovy`.** `grails-app/conf/spring/resources.groovy` with an `import`
  produces `BEAN` symbols, `WIRES_TO` to `demo.CustomerService`/`demo.CustomerDao`, and
  `INJECTS` for `ref("customerDao")` (`RESOLVED`) and `ref("sessionFactory")`
  (`UNRESOLVED`, not defined in the file).
- **Ambiguity.** Two `AmbiguousService` classes keep a single `INJECTS` edge with a null
  target and both candidates (`count=2`); a call through that property is `UNRESOLVED`.
- **Legacy closure action.** `def list = { render(view: "list") }` is **not** published
  as a method: no `groovy:method:demo.LegacyClosureController#list()` symbol exists, and
  the route resolves to the controller class with the description
  `action=list is not statically indexed on demo.LegacyClosureController` rather than a
  false `RESOLVED` method edge.
- **Malformed legacy Groovy.** `BrokenLegacyController.groovy` records a localized
  `GROOVY`/`GROOVY_PARSE` analysis error and every other file is still indexed.
- **Source evidence and determinism.** All new edges carry a `.groovy` source path with
  positive line/column and `GROOVY` evidence type; two consecutive index runs produce
  identical symbols and relationships.
- **End-to-end trace** (integration test, PostgreSQL): `/customer/$id` →
  `CustomerController.show(Long)` → `CustomerService.findByLastName(String)` →
  `db:table:CUSTOMER`, plus the path through `groovy:type:demo.Customer`; every path that
  reaches the table is `INFERRED`. The named route `/customers` traces to
  `list()` → `findAllCustomers()` → `db:table:CUSTOMER`. REST
  (`/api/relationships/trace`, `/api/relationships/database-tables`,
  `/api/entry-points`, `/api/scans`, `/api/symbols/*`) and the MCP `trace_component` tool
  return the same evidence, and table impact keeps `READ` (transitive) and `MAPPING`
  distinct.
- **Normalized model unchanged.** Every produced symbol kind and relationship type is
  from the existing allow-list.

## Regression evidence

- `Grails26IntegrationTests.grails3AndStrutsBehaviourIsUnchanged` re-scans the Slice 8
  Grails 3.x–6.x fixture and asserts the known chain
  `grails:route:grails-app/controllers/demo/UrlMappings.groovy#/customer/$id` →
  `CustomerController.show(Long)` → `CustomerService.findByLastName(String)` →
  `db:table:CUSTOMER` is unchanged, that the 2.6-only route path
  (`grails:route:grails-app/conf/%`) does not appear, and that
  `db:table:customer_order` is still `INFERRED` from the 3.x fixture. It then re-scans
  the Struts/Spring fixture and asserts zero `groovy:`/`grails:` symbols and the
  unchanged `ROUTES_TO`/`RESOLVED` route.
- The Slice 8 suites (`GrailsIndexerTests` 10, `GrailsIntegrationTests` 5) and the whole
  prior backend suite pass unchanged: **170 tests, 0 failures/errors/skips**. The
  `name`-unwrapping change affects only calls whose method text is `name` with a single
  named argument, which no 3.x/Struts fixture contains.

## Known limitations

- **Grails 2.x closure actions** (`def list = { … }`) are intentionally not published as
  methods and their routes do not resolve to an action target; the route keeps the
  controller class with an explicit "action not statically indexed" description. Inside
  a closure action, service calls are therefore not traced. This is a deliberate
  certainty decision, not a parser limitation.
- Only **Grails 2.6** is claimed. Other 2.x layouts (`grails-app/conf/spring/resources.xml`
  XML bean files, `Config.groovy`/`DataSource.groovy` contents, plugin descriptors,
  `BuildConfig.groovy` dependency resolution) are not analyzed.
- Nested constraint closures are only prevented from becoming routes; the constraint
  expressions themselves (regex/`matches:`) are not modeled.
- Grails 2.x `static mapping = { table: 'X' }` written as a labelled statement remains
  unsupported (Groovy parses it as a label); `static mapping = [table: 'X']` and
  `table 'X'` are supported.
- Domain-to-domain GORM relationships (`hasMany`/`belongsTo`/`hasOne`), GORM write
  finders (`save`/`delete`), criteria/`Where` queries, interceptors, GSP rendering,
  plugins and build-tool execution remain out of scope, as in Slice 8.
- Route URIs that neither start with `/` nor are three-digit status codes are skipped, so
  an unusual non-slash wildcard mapping would not be indexed.
- Bounds, error isolation, snapshot isolation and the entry-point-trace boundary are
  unchanged from Slice 8.

No unresolved Slice 8.1 acceptance blocker remains.
