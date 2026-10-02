# Slice 10 — Runtime Repository Selection validation

Date: 2026-10-01. Base commit: `04460b2`. Validated uncommitted Slice 10 changes.

## Files changed

- Runtime/API: `ScanController.java`, `ScanModel.java`, `ScanProperties.java`,
  `ScanService.java`, `application.properties`; added `RepositoryCatalog.java` and
  `RepositoryController.java`.
- Backend tests: `ScanIntegrationTests.java`, new `RepositoryCatalogTests.java`, and the
  existing database/framework/Grails/symbol/traversal integration tests adapted to pass
  a repository ID to `ScanService`.
- Frontend: `app/scan/page.tsx`, new `app/scan/actions.ts`, `lib/api.ts`,
  `lib/api.test.ts`, and `test/pages.test.tsx`.
- Runtime/docs: `docker-compose.yml`, `.env.example`, `README.md`, and this validation
  record. No Dockerfile, schema migration, or dependency manifest changed.

## API, security, UI, and Compose contract

- `GET /api/repositories` returns `{items:[{id,name}], totalCount, truncated}` for at
  most 200 immediate visible non-symlink directories, sorted by ID.
- `POST /api/scans` now requires JSON `{ "repository": "<discovered-id>" }`. Success is
  201 with `Location` and `ScanDetail`; invalid/unavailable selection is 400; unsafe or
  unavailable base configuration is 503. Existing scan list/detail contracts are unchanged.
- Repository IDs are one relative path component. Blank, whitespace-altered, hidden,
  absolute, nested, backslash, `.`/`..`, overlong, missing, non-directory, and symlink
  selections are rejected. Resolution stays lexically and physically inside the base,
  and both validation and inventory require `SecureDirectoryStream` handles.
- `/scan` loads the catalog, renders only a selector and Scan button, sends the selected
  ID through a Server Action, reports completion/failure/backend errors, and retains the
  active snapshot and history display. It has no arbitrary path input or analyzer logic.
- Compose sets container `LEGACY_REPOSITORY_BASE=/workspace/repos` and mounts host
  `${LEGACY_REPOSITORY_BASE:-.}` there `:ro`. Service topology, health gates, loopback
  ports, internal PostgreSQL, frontend-to-backend URL, and Jammy backend runtime remain
  unchanged.

## Commands and results

| Command | Result |
| --- | --- |
| `cd backend/legacy && ./mvnw -q -Dtest=RepositoryCatalogTests test` | 5 passed |
| `cd backend/legacy && ./mvnw -q -Dtest=ScanIntegrationTests test` | 13 passed |
| `cd backend/legacy && ./mvnw test` | 178 passed, 0 failed; BUILD SUCCESS |
| `cd frontend/legacy-ui && npm test -- --run lib/api.test.ts test/pages.test.tsx` | 29 passed |
| `cd frontend/legacy-ui && npm test` | 51 passed, 0 failed |
| `cd frontend/legacy-ui && npm run build` | Passed; `/scan` remained dynamic |
| Isolated `docker compose ... config` | Passed: `/workspace/repos` mount is read-only; backend/frontend ports bind `127.0.0.1`; PostgreSQL has no published port; internal DB/API URLs unchanged |
| Isolated `docker compose ... up --build -d --wait` | `postgres`, `backend`, and `frontend` built and became healthy in dependency order |
| Live REST, UI Server Action, MCP, mount, and hash checks | Passed; details below |
| `docker compose -p legacy-slice10-validation down --volumes` | Removed only the disposable validation stack and database volume |

The first discovery request in the disposable validation setup returned 503 because
`mktemp` created the host base with mode `0700`. Changing that fixture directory to
`0755` allowed the non-root backend container to read it; no application or Compose
setting changed.

## Acceptance evidence

- `LEGACY_REPOSITORY_BASE` is fixed to `/workspace/repos` in the backend container. Compose
  mounts `${LEGACY_REPOSITORY_BASE:-.}` there read-only. No Dockerfile changed.
- `GET /api/repositories` returned only `legacy-grails` and `legacy-struts`, ordered by ID.
  A hidden directory, ordinary file, and symlink were excluded. Unit coverage verifies
  the 200-item bound, deterministic ordering, and truncation metadata.
- Unit and REST coverage rejects traversal, absolute, nested, hidden, missing,
  non-directory, and symlink selections before scan creation. The live traversal,
  absolute, hidden, missing, non-directory, and symlink requests returned 400 and scan
  count remained unchanged.
- REST scans of both repositories completed with distinct IDs and roots
  `/workspace/repos/legacy-struts` and `/workspace/repos/legacy-grails`. The second scan
  became active; the first remained queryable with its 16-file inventory.
- The production `/scan` page rendered both options. Submitting its actual Server Action
  for `legacy-struts` returned 303 with a completed scan ID; REST showed that snapshot as
  active and history count increased to three.
- MCP `list_entry_points` freshness matched the REST `activeScanId` after both the REST
  switch and the UI-triggered switch.
- The live mount reported `RW=false`; `touch /workspace/repos/write-must-fail` failed with
  `Read-only file system`. Aggregate source hash before and after three scans was
  `ee817a8c3409e9b5661505bfeca2515992ddc9d7645a1fe97ddcc7e4a8279a80`.
- Snapshot lifecycle, failed-scan isolation, Git SHA collection, immutability, and prior
  analyzers remain covered by the full backend suite. Flyway validated and applied the
  existing V1–V4 migrations on the disposable database.
- No schema migration and no dependency addition were required. Existing
  `scan.repository_root` records the resolved selected root; all indexed evidence remains
  scan-scoped.

## Known limitations

- Discovery intentionally exposes only immediate visible child directories, returns at
  most 200, and has no continuation cursor; `truncated` and `totalCount` are explicit.
- Repository scans remain synchronous and there is one global active completed snapshot
  across all selectable repositories.
- Adding a repository under the mounted base needs only a UI reload. Changing the host
  base itself requires recreating the backend container so Docker can replace the mount.
- Secure repository access retains the existing Linux `SecureDirectoryStream`
  requirement and fails closed on unsupported filesystems.

No Slice 10 acceptance blocker remains.
