# Slice 2 — Java Symbol Index validation

Date: 2026-09-30. **Acceptance passed.** No commit made; root commit SHA unavailable.
JavaParser core + Symbol Solver: **3.28.2**, validated on OpenJDK **21.0.12.1**
with explicit `JAVA_21` parser configuration and a Java 21 switch-pattern fixture.

## Commands and test counts

Commands ran in `backend/legacy` unless noted.

| Command | Result |
| --- | --- |
| `./mvnw dependency:resolve` | JavaParser core and Symbol Solver 3.28.2 resolved |
| `./mvnw -DskipTests compile` | Passed |
| `./mvnw -Dtest=JavaSymbolIndexerTests,SymbolIntegrationTests test` | Final targeted run: **18 passed**, 0 failures/errors/skips |
| `./mvnw test` | Final full suite, run once: **41 passed**, 0 failures/errors/skips; compilation passed |
| `cd frontend/legacy-ui && npm run build` | Run once at the end; production build and TypeScript passed |

Final counts: 12 Java indexer tests, 6 symbol integration tests, 23 existing
backend tests. Targeted development failures identified generic ancestor type
substitution and an overly narrow substring-search assertion; both were fixed
before final validation. No acceptance checks were removed or disabled.

Logs: `/tmp/legacy-slice2-targeted.log`, `/tmp/legacy-slice2-final-backend.log`,
`/tmp/legacy-slice2-final-frontend.log`. PostgreSQL integration uses the existing
Testcontainers support. No additional Compose, packaged-JAR or standalone HTTP
smoke was repeated; REST behavior was exercised by Slice 2 integration tests.

## Acceptance evidence

- Packages, classes/interfaces, nested member classes, methods, fields, overloads,
  canonical parameter types and source ranges match fixtures. IDs and complete
  relationship lists are identical across repeated scans.
- All six relationships covered: CONTAINS, IMPORTS, EXTENDS, IMPLEMENTS,
  OVERRIDES and CALLS. Tests distinguish same-named methods on different types,
  overloaded calls, generic overrides, private/static hiding, and invalid overrides.
- Missing external library/base types and incomplete classpaths produce
  UNRESOLVED references, never invented targets. Unconfirmed `@Override` intent
  is INFERRED. Malformed Java records a file error while other files complete.
- Duplicate qualified declarations, conflicting explicit/wildcard imports and
  ambiguous null overloads do not silently select a confirmed target. REST
  simple-name searches retain both same-named type candidates.
- Secure rereads reject changed hashes and symlink replacements. Resolution uses
  only inventoried source ASTs and JDK types. No target builds or code execution.
  Argument literals/raw parser messages are excluded from returned evidence.
- Flyway V2 applies on fresh PostgreSQL and upgrades a V1 database while retaining
  its completed active snapshot. New tables reuse immutable-evidence guards and
  enforce same-scan symbol/file references.
- A paused publication exposes only the previous active analysis. Failed symbol
  insertion rolls back files and symbols and preserves the active scan. Committed
  RUNNING/FAILED staging rows remain invisible. Published evidence rejects edits.
- Deleted symbols disappear from the next active snapshot while historical rows
  remain. Repeated persisted results are logically identical.
- REST search/detail/usages verify evidence, freshness, encoded stable IDs,
  candidate lists, pagination/truncation, 400/404 responses and no-active behavior.
- Only Slice 2 implemented; frontend and Compose configuration unchanged.

## Known limitations

- Source-only plus JDK classpath; third-party jars are neither loaded nor fetched.
  Maven/nonstandard layouts are covered through the inventory and declared type
  names, not build-tool execution. Missing jar types remain unresolved.
- Constructors, object-creation/method-reference calls, anonymous bodies,
  local/enum/record/annotation types are outside this baseline. Named unsupported
  types receive diagnostics. No framework or dynamic-dispatch analysis.
- Static/wildcard import edges retain unresolved descriptions; ordinary references
  may still resolve using imports. External JDK targets have descriptions rather
  than indexed symbol rows. Override inference is annotation intent only.
- Generic signatures use erasure; unresolved parameters use `?Type` markers.
  IDs are stable for identical source/classpath, but may improve when dependencies
  become available. Package location is a representative declaration.
- Java files above 8 MiB or with unknown encoding are not semantically indexed;
  inventory remains and errors explain the omission. ASTs are retained per scan.
- Slice 1's Linux secure-filesystem and synchronous-scan limits remain. A source
  change between inventory and parsing produces a localized error.

No unresolved Slice 2 acceptance blocker remains.
