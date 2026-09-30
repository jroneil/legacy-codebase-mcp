# Slice 1 — Scan Foundation validation

Date: 2026-09-30

Status: **Slice 1 acceptance passed**. No later slice implemented. No commit made.
Commit under test: unavailable; the workspace root has no Git repository. The
unchanged frontend has its existing `7852bb7` baseline commit and a clean working
tree. The earlier Slice 0 root-commit item remains outside this implementation.

## Implementation and files

Modified:

- `backend/legacy/pom.xml`
- `backend/legacy/src/main/resources/application.properties`
- `backend/legacy/src/test/java/com/oneil/legacy/LegacyApplicationTests.java`
- `README.md`

Added:

- `backend/legacy/src/main/resources/db/migration/V1__scan_foundation.sql`
- `backend/legacy/src/main/java/com/oneil/legacy/scan/ScanProperties.java`
- `backend/legacy/src/main/java/com/oneil/legacy/scan/ScanModel.java`
- `backend/legacy/src/main/java/com/oneil/legacy/scan/RepositoryInventory.java`
- `backend/legacy/src/main/java/com/oneil/legacy/scan/GitRevisionReader.java`
- `backend/legacy/src/main/java/com/oneil/legacy/scan/ScanStore.java`
- `backend/legacy/src/main/java/com/oneil/legacy/scan/ScanService.java`
- `backend/legacy/src/main/java/com/oneil/legacy/scan/ScanController.java`
- `backend/legacy/src/test/java/com/oneil/legacy/PostgresTestSupport.java`
- `backend/legacy/src/test/java/com/oneil/legacy/scan/RepositoryInventoryTests.java`
- `backend/legacy/src/test/java/com/oneil/legacy/scan/ScanIntegrationTests.java`
- `backend/legacy/src/test/resources/fixtures/inventory/src/Example.java`
- `backend/legacy/src/test/resources/fixtures/inventory/config/application.properties`
- `backend/legacy/src/test/resources/fixtures/inventory/broken.xml`
- `docs/validation/SLICE_01_VALIDATION.md`

The existing PostgreSQL Compose service was reused unchanged. No frontend
source, generated project structure, or existing dependency versions changed.

Dependencies added, with versions managed by Spring Boot 4.1.1:

| Dependency | Resolved version | Scope |
| --- | --- | --- |
| `org.springframework.boot:spring-boot-starter-flyway` | 4.1.1 | Application |
| `org.flywaydb:flyway-database-postgresql` | 12.4.0 | Application |
| `org.testcontainers:testcontainers-postgresql` | 2.0.5 | Test only |

No JavaParser, MCP, JSqlParser, Groovy parser, or frontend dependency was added.
The PRD's earlier MCP compatibility wording conflicts with the slice ordering;
the user's explicit scope and implementation plan defer that work to Slice 6.

## Design verified

Flyway creates `scan`, `source_file`, `analysis_error`, and `active_scan`.
Hibernate is configured to validate, never create/update the schema. This slice
uses JDBC, not JPA entities; the PostgreSQL migration, constraints, triggers,
and integration suite validate the actual schema.

Scan creation and RUNNING transitions commit separately. Inventory is collected
read-only outside a database transaction. Publication inserts evidence, marks
the scan COMPLETED, and switches the active pointer in one transaction. The
pointer row serializes concurrent publishers. Failure rolls back publication,
records FAILED in a separate transaction, and preserves the active pointer.
Terminal snapshots and their evidence reject mutations at the database level.
The active pointer's composite foreign key accepts only COMPLETED scans.

Operational queries may expose PENDING/RUNNING/FAILED metadata, but never their
inventory. Read transactions use repeatable-read isolation for consistent
metadata, active pointer, and inventory pages. A new snapshot is always a fresh
file set; historical completed snapshots remain accessible by ID.

## Commands and results

Environment: Linux; OpenJDK 21.0.12.1; Maven wrapper 3.3.4 / Maven 3.9.16;
PostgreSQL `17.11-alpine`; Docker 29.6.1 / Compose v5.3.1;
Node 24.18.0 / npm 12.1.0; Next.js 16.3.7.
Node was loaded from the existing NVM installation. Commands used approved
escalated execution because the sandbox previously failed to initialize with
`mountinfo path is not absolute`.

| Command | Result |
| --- | --- |
| `cd backend/legacy && ./mvnw test` | Passed: initial full suite, 22 tests, 0 failures/errors/skips |
| `./backend/legacy/mvnw -f backend/legacy/pom.xml clean verify` | Passed after UTF-16 coverage was added: **23 tests, 0 failures, 0 errors, 0 skipped**; executable JAR built |
| `cd frontend/legacy-ui && npm run build` | Passed: TypeScript, production build, and prerendering |
| `docker compose -p legacy-codebase-mcp-slice1-validation config --quiet` | Passed |
| `docker compose -p legacy-codebase-mcp-slice1-validation up -d --wait postgres` | Passed; fresh named volume, healthy PostgreSQL, loopback port 54339 |
| `java -jar target/legacy-0.0.1-SNAPSHOT.jar --server.port=18081` | Started against the fresh Compose database with standard datasource environment |
| HTTP health/list/create/detail checks using Python `urllib.request` | Passed; results below |
| `docker compose -p legacy-codebase-mcp-slice1-validation exec -T postgres psql -U legacy -d legacy` with validation SELECTs | Flyway V1 successful; expected statuses, counts, and active pointer |
| `git -C frontend/legacy-ui status --short` | Empty: frontend unchanged |
| `docker compose -p legacy-codebase-mcp-slice1-validation down --volumes` | Removed this validation's new database, network, and volume only |

One initial test invocation mistakenly used `./mvnw` from the root and exited
127; it was rerun from the backend directory. No application/test failures
occurred in the full suites. The generated Mockito dynamic-agent warning
remains non-fatal. No tests were disabled or weakened.

Final Surefire counts:

| Test class | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| `RepositoryInventoryTests` | 10 | 0 | 0 | 0 |
| `ScanIntegrationTests` | 12 | 0 | 0 | 0 |
| `LegacyApplicationTests` | 1 | 0 | 0 | 0 |
| **Total** | **23** | **0** | **0** | **0** |

Tests use a disposable Testcontainers PostgreSQL database, not H2 or the
configured application database. The integration suite resets only that
container's test tables. Docker is mandatory; unavailable Docker fails tests
rather than skipping integration coverage.

## Acceptance evidence

| Requirement | Evidence/result |
| --- | --- |
| Clean migration and repeat startup | Testcontainers startup applied V1; Flyway validation passed and repeat migration executed 0 migrations; packaged application also applied V1 on a fresh Compose volume |
| Valid nested inventory | All seven supported extensions inventoried with relative paths, byte sizes, exact SHA-256, and encoding |
| Default ignores | `.git`, `target`, `build`, `.gradle`, `node_modules` excluded at root and nested levels |
| Unsupported/binary inputs | Unsupported extensions and NUL-bearing binary fixture omitted |
| Symlink escape prevention | File/directory escapes, cycles, dangling links, root links, parent links, and Git metadata links/traversal tested; secure no-follow directory handles used |
| Localized errors | Actual POSIX-unreadable files/directories recorded safely; other files completed; error persistence verified |
| Malformed source and encoding | Malformed Java/XML inventoried without parsing; invalid UTF-8 retained with exact byte hash, UNKNOWN encoding and UNRESOLVED error; BOM-marked UTF-16LE/BE recognized |
| Read-only target | File contents and modification times unchanged across scans; real Git fixture stays clean; scanner never runs Git, hooks, builds or target code |
| Git SHA and analyzer version | Real committed Git fixture's SHA equals persisted SHA; loose/detached/packed refs tested; configured analyzer version persists |
| Completed scan becomes active | Service, PostgreSQL and HTTP checks agree on active scan UUID |
| Repeat scans / no duplicates | Identical input produces identical file records in separate snapshots; per-scan unique path constraint enforced |
| Deleted files | Third snapshot has two files after fixture deletion; earlier snapshot still has three |
| Failed scan preserves active | Missing-root failure and injected duplicate-row publication failure both preserve old active snapshot |
| Atomic publication / rollback | Publication transaction paused before commit: independent service and HTTP readers see RUNNING metadata, empty replacement inventory and previous active snapshot; after commit they see complete replacement |
| No partially staged inventory returned | Even a deliberately committed staging row is hidden while RUNNING; failed publication leaves zero persisted file rows |
| Concurrent publications | Second writer waits for first publication transaction; both snapshots complete intact and the last publication is active |
| Immutable snapshots | Invalid transitions, noncompleted active targets, terminal metadata changes, and terminal evidence insert/update/delete rejected by PostgreSQL |
| REST contract | POST 201 and Location; GET list/detail; active ID; bounded pages and truncation; invalid bounds/UUID 400; missing UUID 404; missing root configuration 503 |
| Schema authority | Flyway migration and database guards own DDL; no Hibernate create/update and no application schema creation in tests |
| Scope | No semantic analysis, MCP, framework wiring, SQL parsing, or frontend features |

## Packaged application smoke results

The smoke runner copied the project fixture files to its own temporary
repository. It changed only this disposable fixture to test deletion/failure.
The application used `ANALYZER_VERSION=slice1-smoke` and externalized credentials.

- `/actuator/health`: HTTP 200, `UP`.
- Initial list: no active scan.
- First POST: COMPLETED, 3 files, 0 errors.
- Second POST: COMPLETED, 3 files, identical inventory and unchanged target hashes.
- Third POST after deleting the disposable fixture's Java file: COMPLETED,
  2 files, 0 errors; historical first scan still has 3 files.
- Fourth POST while the disposable root was temporarily unavailable: FAILED,
  0 files; third scan remains active.
- `limit=1` detail page: 1 item and `truncated=true`.
- Direct SQL confirms Flyway version 1 succeeded and scan states/counts match.
- PostgreSQL bound only to `127.0.0.1:54339`; backend used its default loopback
  binding at port 18081.

Smoke servers were stopped. The validation database was removed and its
private temporary credential file deleted. Existing user databases were not
modified. Local command logs are in `/tmp/legacy-slice1-*.log`, and the smoke
summary is `/tmp/legacy-slice1-smoke-results.json`; generated build artifacts
remain ignored by the existing root rules.

## Known limitations and deferred work

- Validated on Linux with `SecureDirectoryStream`; unsupported filesystems
  fail closed. All symlinks, including symlinked root/parent paths, are excluded.
- Full synchronous scans retain file/error metadata in memory until publication.
  No incremental scan, worker queue, retention, performance threshold, or
  automatic crash recovery. An interrupted PENDING/RUNNING scan remains
  inactive; subsequent POST requests can create a new scan.
- One configured repository and one global active pointer. Keep that root's
  contents stable during scanning. Per-file metadata detects ordinary changes,
  but this is not an atomic filesystem checkout and Git SHA does not certify
  that the working tree is clean.
- UTF-8 and BOM-marked UTF-16 are supported. Other nonbinary text is retained
  as UNKNOWN with a decoding error where detected; binary classification uses
  a NUL-byte heuristic. Custom ignores and broader encoding detection deferred.
- Normal Git directories, detached HEAD and packed refs supported. Worktree/
  submodule metadata outside the root is not followed and records an error.
  Packed refs over 4 MiB are rejected safely. SHA may be null for non-Git or
  unborn repositories.
- Errors contain safe categories rather than raw exception details. No source
  snippets are returned; secret-snippet redaction belongs to later interfaces.
- No root commit identifier exists. No commit was requested or created.

No unresolved blocker for the Slice 1 acceptance gate remains.
