# Slice 10.1 — Repository Browser UX validation

Date: 2026-10-02. Base commit: `a3c575b`. Validated uncommitted Slice 10.1 changes.

## Commands and results

| Command | Result |
| --- | --- |
| `cd backend/legacy && ./mvnw -Dtest=RepositoryCatalogTests,ScanIntegrationTests test` | 20 passed |
| `cd backend/legacy && ./mvnw -q -Dtest=RepositoryCatalogTests test` | 7 passed after the final normalization check |
| `cd frontend/legacy-ui && npm test -- --run lib/api.test.ts test/pages.test.tsx` (Node 24 glibc container) | 30 passed |
| `cd backend/legacy && ./mvnw test` | 180 passed, 0 failed; BUILD SUCCESS |
| `cd frontend/legacy-ui && npm test` (Node 24 glibc container) | 52 passed, 0 failed |
| `cd frontend/legacy-ui && npm run build` (Node 24 glibc container) | Passed; `/scan` remains dynamic |
| Isolated `docker compose -p legacy-slice101-validation config` | Three services; repository bind is read-only; backend/frontend bind to loopback; PostgreSQL is internal; backend uses `postgres:5432`; frontend uses `http://backend:8080` |
| Isolated `docker compose -p legacy-slice101-validation up --build -d --wait` | Images built and all three services became healthy in dependency order |
| Live repository browser, scan, UI, MCP, mount, and hash checks | Passed; evidence below |
| `docker compose -p legacy-slice101-validation down --volumes` | Removed only the disposable stack and database volume |

Host `npm` was unavailable. Frontend tests and the standalone production build ran in
`node:24-bookworm-slim` against the existing locked dependencies; the Compose build
also rebuilt the production frontend in its declared `node:24-alpine` stages.

## Acceptance evidence

- `GET /api/repositories` returned `{path:"", parent:null}` with only `legacy` and
  `other`. `GET ...?path=legacy` returned `legacy/grails-app` and
  `legacy/struts-app` in deterministic order with `parent:""`. Unit coverage verifies
  nested parents, the 200-result bound, total count, truncation, 32-component depth,
  and 1,000-character path bounds.
- Hidden directories, ordinary files, and final/outside symlinks were absent from
  listings. Live requests for `.hidden`, `ordinary-file.txt`, `escape-link`, `..`, and
  a missing nested directory returned 400. Unit coverage also rejects absolute paths,
  whitespace changes, repeated/trailing separators, `.`/`..`, hidden components,
  intermediate symlinks, final symlinks, non-directories, physical escape, missing
  paths, unsafe bases, and unsupported secure directory access.
- `/scan` rendered folder navigation at the root with no scan button. The nested
  `legacy` view rendered both child links, Up, the current relative folder, and the
  explicit **Scan this repository** action. Frontend tests cover root, child/Up
  navigation, nested POST payload, empty folders, stale paths, and backend/action
  errors.
- REST scans of `legacy/struts-app` and `legacy/grails-app` completed without a
  container restart. History contained both immutable snapshots; the second root
  `/workspace/repos/legacy/grails-app` was active and the first remained queryable.
- MCP `list_entry_points` freshness matched the second active scan over protocol
  `2025-06-18`. The backend container ID remained `120f091582a9` throughout both
  scans.
- Compose required an explicit `LEGACY_REPOSITORY_BASE`, mounted it at
  `/workspace/repos` with `readWrite=false`, and retained three-service startup
  ordering. `touch /workspace/repos/write-must-fail` failed with `Read-only file
  system`.
- Aggregate fixture hash before and after browsing and both scans was
  `edf8a473dd9eecc96f4ec459b7848a1db060e89491032ed7e0c4cd533bf4a53a`.
- Existing Flyway V1–V4 schema and dependencies were unchanged. Scan snapshot, active
  pointer, history, REST, and MCP behavior remained covered by the full backend suite.

## Known limitations

- Each browse response returns at most 200 immediate child directories and has no
  continuation cursor; `totalCount` and `truncated` make the bound explicit.
- Browser paths are capped at 32 components and 1,000 characters. The mounted base
  itself is a namespace root and cannot be submitted as a repository.
- A visible directory is selectable without trying to infer whether it is an
  application root. Scans remain synchronous, with one global active completed
  snapshot.
- Adding directories under the mounted base needs only a page reload. Changing the
  host base requires recreating the backend container so Docker can replace the bind
  mount.
- Secure browsing and inventory retain the Linux `SecureDirectoryStream` requirement
  and fail closed on unsupported filesystems.

No Slice 10.1 acceptance blocker remains.
