# Slice 3 — Struts 1 + Spring XML Wiring validation

Date: 2026-09-30. **Acceptance passed.** Base commit:
`814bfa08e7b2ca6b78398b5e6b706769230f2b27`; tested with uncommitted Slice 3 changes.
No dependencies added. No commit made.

## Commands and results

Backend commands ran in `backend/legacy` (equivalent root wrapper invocations
used `./backend/legacy/mvnw -f backend/legacy/pom.xml`).

| Command | Result |
| --- | --- |
| `./mvnw -q -DskipTests compile` | Passed |
| `./mvnw -Dtest=FrameworkIndexerTests test` | Development run exposed import-cycle discovery bug; fixed |
| `./mvnw -Dtest=FrameworkIndexerTests,FrameworkIntegrationTests test` | Final targeted run: **22 passed** (14 parser/indexer, 8 PostgreSQL/REST integration), no failures/errors/skips |
| `./mvnw test` | Run once at end: **63 passed**, no failures/errors/skips; compilation passed |
| `cd frontend/legacy-ui && PATH=/home/ai-dev/.nvm/versions/node/v24.18.0/bin:$PATH npm run build` | Run once at end: production build and TypeScript passed; frontend unchanged |
| `git diff --check` | Passed |

Logs: `/tmp/legacy-slice3-targeted.log`, `/tmp/legacy-slice3-final-backend.log`,
`/tmp/legacy-slice3-final-frontend.log`. Integration tests used existing PostgreSQL
17.11 Testcontainers support. No standalone HTTP, packaged-JAR or full Compose
smoke repeated; REST and migration checks are in the integration suite.

## Acceptance evidence

- `fixtures/struts-spring` covers web.xml servlet/config mapping, normal Action,
  DispatchAction, ActionForm, local/global forwards, JSP rendering, action forwarding,
  service/DAO interfaces and implementations, property/constructor injection,
  imported Spring XML, ambiguous beans, missing class, and malformed XML.
- All five required relationships are persisted with XML source path and positive
  line/column: ROUTES_TO, FORWARDS_TO, RENDERS, INJECTS, WIRES_TO. Existing Java
  identities are reused. Repeated indexes and persisted traces are identical.
- `GET /api/entry-points?path=/customer/search` identifies the fixture route;
  `GET /api/entry-points/trace?entryId=...` proves the configuration chain
  `/customer/search -> demo.CustomerAction -> demo.CustomerServiceImpl -> demo.CustomerDAOImpl`.
  Each edge retains evidence and resolution. `demo.CustomerService` and
  `demo.CustomerDAO` interface bindings to those implementations are asserted.
  Action-to-bean class matching is explicitly INFERRED, so the complete trace
  is INFERRED; runtime delegation is not claimed.
- Dispatch candidates retain `request parameter operation=search/save` conditions;
  the parameter key is never interpreted as a method name. Helpers are excluded.
- Duplicate IDs, multiple by-type implementations, independent contexts, missing
  references and ambiguous routes retain candidates or UNRESOLVED edges. Unique
  by-type wiring is INFERRED. Explicit references take precedence. Traces stop
  at ambiguity/cycles and report depth bounds; REST bounds/404s/freshness tested.
- External entities, remote imports, root escapes, changed hashes and symlink
  substitutions cannot supply XML evidence. Legacy external DOCTYPEs need no
  downloads. Malformed XML/import cycles are localized; missing classes do not
  fail the snapshot. Input hashes are unchanged. Property secrets and external
  forward credentials are absent from returned evidence.
- Flyway V3 applies on fresh PostgreSQL and upgrades a populated V2 snapshot
  without altering its Java symbols or active pointer. Existing immutable-row
  guards remain effective. No Hibernate schema creation.
- Concurrent uncommitted publication exposes only the old active framework model;
  committed RUNNING/FAILED staging rows remain invisible. A failed framework FK
  insertion rolls back all symbols/relationships and preserves the active scan.
  Deleted mappings disappear from the new snapshot; historical rows remain.
- Query-parameter symbol detail/usages support path-containing framework IDs.
  Only Slice 3 implemented; no SQL/table, MCP, Grails or frontend features.

## Known limitations

- Static configuration trace, not runtime Struts/Spring execution. Dispatch methods
  are conditional inferred candidates; access checks/request values are unavailable
  in the existing Java model. Source-only classpath limitations remain.
- Top-level Spring beans, direct property/constructor refs and setter-based byType
  wiring are supported. No profile/parent/factory evaluation, collection/inner-bean
  wiring, alias elements, other autowire modes, wildcard imports or merged context
  hierarchies. Factory/abstract beans do not establish runtime class bindings;
  unsupported nested injection remains unresolved. Interface binding uses indexed
  setter types or explicit constructor type attributes.
- Struts module prefixes/custom forwarding conventions are not interpreted;
  action paths are indexed as declared. No runtime selection of a named forward.
- XML limits: 8 MiB/file, 20,000 elements, nesting 100, 100 imports/context. Trace
  limits: depth 8, 100 paths, 200 edges/expansion, 1,000 total edges; bounded output
  carries a truncation flag. Candidate descriptions list at most 20 IDs plus count.
- Existing synchronous scan and secure-filesystem constraints remain. No blocker.
