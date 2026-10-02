# Slice 10.3 — Local Direct Repository Scan validation

Date: 2026-10-02. Base commit: `c274ef6`. Changes are uncommitted.

## Commands and results

| Command | Result |
| --- | --- |
| `cd backend/legacy && ./mvnw -Dtest=MountedRepositoryServiceTests,ScanIntegrationTests test` | 16 passed, 0 failed |
| `cd backend/legacy && ./mvnw test` | 176 passed, 0 failed; build success |
| `docker run --rm -v .../frontend/legacy-ui:/app -w /app node:24-bookworm-slim sh -lc 'npm test && npm run build'` | 50 passed, 0 failed; Next.js production build passed |
| `./scripts/test-run-local.sh` | 6 passed: valid directory, spaces, shell metacharacters/no injection, file rejection, missing rejection, canonical symlink/`..` resolution |
| `POSTGRES_PASSWORD=config-only docker compose config` plus assertions | Three services; fixed `/workspace/target`; read-only bind; loopback backend/frontend; no PostgreSQL host port; internal DB/API URLs |
| Isolated `run-local.sh` repo A → repo B workflow on ports 18083/13003 | Both stacks healthy; both scans completed; all acceptance assertions passed |

## Acceptance evidence

- The launcher mounted only `fixtures/struts-spring` and then only `fixtures/grails26`
  at `/workspace/target`; `docker inspect` reported `RW=false` for each exact source.
- `touch /workspace/target/slice103-write-probe` failed with `Read-only file system`.
- Repo A completed with 16 files/2 localized errors. Repo B completed with 12 files/1
  localized error and became active.
- Repo A tree hash stayed
  `60b097d5af54ad7fa2f21a074a5874850501bf16231be7eb3d13e6ce64337031`;
  repo B stayed
  `0f460fec303112c9a567f469dde83e6903d26046c2e1f02e102412253b4867d3`.
- Switching repositories changed the backend container ID while PostgreSQL and frontend
  IDs stayed unchanged. PostgreSQL retained two completed snapshots; repo A remained
  queryable and repo B was active.
- `/api/repository` exposed only `name` and `READY`; `/scan` rendered the mounted name,
  scan button, active scan, and history. No host path was returned to the browser.
- REST `list_entry_points` and MCP `list_entry_points` both reported active scan
  `93b3b260-7a5d-4af1-a77f-cc5badcde0f7` with analyzer version
  `slice10.3-validation`.
- `/api/repositories` and `/api/repository-imports` returned 404. No upload or copy was
  used. The analyzer ran no target code.
- Flyway schema, application dependencies, Jammy backend runtime, health ordering,
  loopback bindings, internal PostgreSQL, and snapshot publication model are unchanged.

## Known limitations

- Automated launcher and Compose validation ran on Linux with explicit paths. Native
  GUI pickers require an interactive desktop and were not automated.
- PowerShell was unavailable on the validation host, so `run-local.ps1` was reviewed but
  not executed. Docker Desktop filesystems must provide `SecureDirectoryStream`; the
  backend fails closed when they do not.
- Remote upload/import is intentionally deferred. Scans remain synchronous, and changing
  repositories requires rerunning the launcher.

No Slice 10.3 acceptance blocker remains.
