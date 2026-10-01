# Slice 9 — Containerized Distribution validation

Date: 2026-10-01. Base commit: `20d673c`. Validated the uncommitted Slice 9 changes.
No application code changed, so the backend test suite was not re-run.

## Files changed

Added:

- `backend/legacy/Dockerfile`
- `backend/legacy/.dockerignore`
- `frontend/legacy-ui/Dockerfile`
- `frontend/legacy-ui/.dockerignore`
- `.env.example`
- `docker-compose.dev.yml` — host-loopback PostgreSQL port for the native workflow only
- `docs/validation/SLICE_09_VALIDATION.md`

Modified:

- `docker-compose.yml` — rewritten: `postgres`, `backend`, `frontend` with health-gated
  ordering, a read-only repository mount and loopback-only published ports
- `frontend/legacy-ui/next.config.ts` — `output: "standalone"` for the container image
  (`next dev` / `next start` on the host are unaffected)
- `README.md` — Docker Compose is now the default getting-started path

Not changed: analyzer semantics, REST/MCP contracts, persistence schema, Flyway,
Grails/Struts behaviour, frontend functionality, backend source.

## Image/runtime choices

| Image | Stages | Runtime base | Size |
| --- | --- | --- | --- |
| `legacy-codebase-mcp-backend` | `maven:3.9-eclipse-temurin-21` build → `eclipse-temurin:21-jre-jammy` runtime | Java 21 JRE, non-root `app` user, no Maven | 559 MB |
| `legacy-codebase-mcp-frontend` | `node:24-alpine` deps → build → runtime | Node 24, non-root `nextjs` user, `server.js` from `.next/standalone` | 296 MB |

The backend build stage runs `mvn -Dmaven.test.skip=true package` with a BuildKit
`/root/.m2` cache mount; tests are excluded because they need a Docker daemon
(Testcontainers). The frontend runtime copies only `public`, `.next/standalone` and
`.next/static`.

**Why glibc rather than Alpine:** the Alpine/musl Temurin image does not expose
`java.nio.file.SecureDirectoryStream` anywhere in the container — `/`, `/tmp`, ext4
bind mounts and named volumes all report `secure=false` — while the glibc Temurin
image reports `secure=true` on the same overlayfs. `RepositoryInventory` deliberately
refuses to inventory a repository without a secure directory handle ("Secure directory
access is unavailable"), so the Alpine runtime made every scan fail in ~0.05 s with
`FAILED` and 0 files. Switching the runtime base to `eclipse-temurin:21-jre-jammy`
fixes this with **no application change** and no weakening of the Slice 1
secure-filesystem guarantee. Cost: ~120 MB of image size.

## Compose service topology

```text
postgres  (postgres:17.11-alpine, named volume postgres-data, NO host port)
   |  healthcheck: pg_isready -U legacy -d legacy
   v  condition: service_healthy
backend   (built image; SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/legacy;
           LEGACY_REPOSITORY_ROOT=/workspace/target;
           bind mount ${LEGACY_REPOSITORY_ROOT} -> /workspace/target read-only;
           SERVER_ADDRESS=0.0.0.0; healthcheck /actuator/health)
   |  healthcheck: wget http://127.0.0.1:8080/actuator/health
   v  condition: service_healthy
frontend  (built image; LEGACY_API_BASE_URL=http://backend:8080;
           HOSTNAME=0.0.0.0 PORT=3000; healthcheck GET /)
```

Only the backend and frontend publish ports, both on `127.0.0.1`. PostgreSQL is
reachable only from the Compose network. `restart: unless-stopped` on all three.
`docker-compose.dev.yml` adds `127.0.0.1:${POSTGRES_PORT:-54329}:5432` for the native
workflow and is not used by the default stack.

## Commands run

| Command | Result |
| --- | --- |
| `docker compose config` | Rendered successfully; assert mount `read_only: true`, `host_ip: 127.0.0.1`, `postgres ports: NONE`, `SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/legacy`, `LEGACY_API_BASE_URL=http://backend:8080` |
| `docker compose build` | Both images built from clean cache-less state (Maven and npm dependencies downloaded inside Docker) |
| `docker compose up -d --wait` (fresh volume) | `postgres`, `backend`, `frontend` all reached **healthy** in **23.3 s** (ordered) |
| `curl http://127.0.0.1:8080/actuator/health` | `{"status":"UP"}` HTTP 200 |
| `curl http://127.0.0.1:3000/` | HTTP 200, `<title>Legacy Codebase Explorer</title>` |
| MCP `initialize` → `tools/list` → `tools/call` | Protocol `2025-06-18`, server `legacy-codebase-mcp 0.0.1`, 8 tools, real data |
| `POST /api/scans` | HTTP 201, `COMPLETED`, 12 files, 1 error, `repositoryRoot=/workspace/target` |
| `docker compose down` → `up -d --wait` | Scan history preserved |
| `docker compose down --volumes` → `up -d --wait` | Fresh database: `activeScanId: None`, `scans: 0` |
| `cd frontend/legacy-ui && npm test` | 47 passed, 0 failed |
| `cd frontend/legacy-ui && npm run build` | Passed; `.next/standalone` emitted |

## Startup/health results

- `docker compose ps` after startup:

```text
SERVICE    STATUS                    PORTS
backend    Up (healthy)              127.0.0.1:8080->8080/tcp
frontend   Up (healthy)              127.0.0.1:3000->3000/tcp
postgres   Up (healthy)              5432/tcp          <- no host publication
```

- Ordering worked as intended: PostgreSQL became healthy first, then the backend, then
  the frontend; `docker compose up --wait` returned only after all three were healthy.
- Backend reached PostgreSQL over the Compose network, confirmed in its log:
  `Database JDBC URL [jdbc:postgresql://postgres:5432/legacy]`.
- Flyway ran on the fresh volume (V1–V4 applied at startup; the scan then succeeded).
- The frontend reached the backend over the Compose network: `GET /entry-points`
  rendered the Grails route `/customer/$id` from the active scan, and `GET /scan`
  rendered the analyzer label `slice9-container-check`.

## Read-only mount and source-integrity evidence

- `docker inspect legacy-codebase-mcp-backend-1` →
  `/data/.../fixtures/grails26 -> /workspace/target RW=false`.
- Writing inside the container was refused:
  `touch /workspace/target/probe-please-fail` → `Read-only file system`.
- The mounted fixture tree hashed **identically before and after the scan**:
  `sha256 of all file hashes = 9fe61c8891c6aa994ab208034bd552031ac5e64ccbc0b7c5326a3af239411f4b`
  (before and after). The analyzer read the repository and wrote nothing to it.

## Scan evidence

```text
repositoryRoot   /workspace/target
status           COMPLETED
fileCount        12
errorCount       1        (the fixture's intentionally malformed Groovy file)
analyzerVersion  slice9-container-check
gitCommitSha     null     (the fixture directory is not a Git working tree)
```

Queryable through all three interfaces against the same snapshot:

- MCP `list_entry_points` returned freshness `scanId b00ffb83-…` and the seven Grails
  2.6 routes (`/customer/$id`, `/customers`, `/customer`, `/product/$id?`,
  `/legacy/list`, `/500`, dynamic `/$controller/$action?/$id?`);
  `trace_component` on `/customer/$id` returned `SELECTED`.
- REST `GET /api/scans` reported the same `activeScanId`.
- The UI rendered the same evidence.

## Persistence evidence

- After the successful scan: `scans: 2` (one earlier `FAILED` scan from the Alpine
  diagnosis plus the `COMPLETED` scan), `activeScanId` set.
- `docker compose down` then `docker compose up -d --wait`: `activeScanId` unchanged and
  both snapshots still listed — scan history survives container removal.
- `docker compose down --volumes` then `up -d --wait`: `activeScanId: None`, `scans: 0` —
  the disposable volume and its history were removed as intended.

## Ports/URLs

| Endpoint | Binding |
| --- | --- |
| UI | `127.0.0.1:3000` → frontend `3000` |
| REST | `127.0.0.1:8080` |
| MCP | `127.0.0.1:8080/mcp` |
| Health | `127.0.0.1:8080/actuator/health` |
| PostgreSQL | not published; Compose-internal `postgres:5432` only |

`BACKEND_PORT` and `FRONTEND_PORT` in `.env` change the host ports only; both remain
bound to `127.0.0.1`.

## README changes

Restructured so Compose is the default path and native Java/Node is secondary:

1. **Why this project exists** — the analyzer does the repeatable archaeology once;
   agents consume structured, cited evidence over MCP; no LLM API is required in the
   application.
2. **Quick start** — `docker compose up --build` after `cp .env.example .env`.
3. **Required `.env` values** — `LEGACY_REPOSITORY_ROOT` and `POSTGRES_PASSWORD`, plus
   the optional variables and their defaults.
4. **URLs** — UI, REST, MCP and health.
5. **Create a scan** — the `POST /api/scans` command, the synchronous/large-repository
   caveat, and the same evidence through REST, MCP and the UI.
6. **Point at a different repository** — edit `.env` and
   `docker compose up -d --force-recreate backend`, because the root is read at startup.
7. **Stop, start, and reset** — `stop`/`start`/`down`/`up` keep history;
   `down --volumes` is called out as destructive.
8. **Security boundary** and **Container images** — read-only mount, no PostgreSQL port,
   loopback binding, credentials in gitignored `.env`.
9. **Native developer workflow (contributors)** — database via
   `docker-compose.dev.yml`, host backend and frontend, tests, and the note that
   `SERVER_ADDRESS` stays loopback-only outside the container.

Pre-existing manual setup instructions that a normal user no longer needs were replaced
by the Compose path; the retained per-capability sections (Java index, Struts, database,
traversal, MCP, frontend, Grails) still describe the analyzer.

## Known limitations

- **Image size**: the backend runtime is 559 MB. The glibc Temurin base is required for
  `SecureDirectoryStream`; the Alpine equivalent is ~120 MB smaller but breaks scanning.
- **First build is slow and needs network**: Maven Central, npm and Docker Hub. Later
  builds reuse the BuildKit `/root/.m2` cache and the npm layer cache.
- **Loopback only**: the stack is for local use. There is no TLS, authentication or
  reverse proxy, and no Kubernetes/manifest variant.
- **`LEGACY_REPOSITORY_ROOT` is read at container start**, so changing repositories
  requires recreating the backend container; there is no per-request path.
- **Bind-mount specifics**: the host path must exist and be a physical directory
  (symlinked roots are still rejected), and on Docker Desktop for macOS/Windows the
  bind-mount filesystem may not report secure directory stream support, which would make
  scans fail closed. Validated on Linux only.
- **PostgreSQL is not published by default**; host-based backend development needs the
  `docker-compose.dev.yml` override.
- **`docker-compose.dev.yml` is an extra file** beyond the required deliverables; it
  exists so the documented native workflow keeps working without exposing PostgreSQL in
  the default stack.
- Images are built locally from source; nothing is published to a registry and there is
  no image versioning or SBOM.
- The Compose stack does not expose scan progress; `POST /api/scans` remains synchronous,
  so a very large repository can outlast a default HTTP client timeout.
- No CI pipeline was added to build or scan the images.
- The container validation used the small `fixtures/grails26` repository (12 files) as
  instructed, not a large real repository.

No unresolved Slice 9 acceptance blocker remains.
