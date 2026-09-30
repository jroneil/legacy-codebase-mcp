# Slice 0 — Baseline validation

Date: 2026-09-30

Status: build and runtime validation passed; full acceptance pending root Git
setup and an explicitly authorized baseline commit. No later slice started.

## Source state

Read `AGENTS.md`, `docs/PRD.md`, and `docs/IMPLEMENTATION_PLAN.md` completely
before editing. Also read the frontend's `AGENTS.md`.

The workspace root is not a Git repository. The generated frontend is a nested
Git repository at commit `7852bb7` (`Initial commit from Create Next App`), with
a clean working tree before and after validation. No root commit is available.
No commit was made: AGENTS.md requires an explicit commit request. The plan's
committed-baseline acceptance criterion remains pending; this report does not
claim full Slice 0 completion.

## Changes

- Root `.gitignore`: build output, dependencies, local secrets, logs and editor files.
- Root `docker-compose.yml`: PostgreSQL 17.11 Alpine, persistent volume,
  health check, required external password, loopback-only port (default 54329).
- Root `README.md`: prerequisites, database environment, build/run instructions,
  security defaults and current scope.
- This validation record.

Generated backend/frontend code, tests, dependency declarations, and lockfile
were left unchanged. The initial PostgreSQL service is allowed in Slice 0 and
is needed by the existing generated JPA context test and the future Slice 1.
No scanner, application schema, Flyway, MCP, or UI features were added.

## Toolchain

| Tool | Validated version |
| --- | --- |
| Java | OpenJDK 21.0.12.1 |
| Maven wrapper | 3.3.4, downloading Maven 3.9.16 |
| Spring Boot | 4.1.1 |
| Node | 24.18.0 |
| npm | 12.1.0 |
| Next.js | 16.3.7 |
| Docker / Compose | 29.6.1 / v5.3.1 |
| PostgreSQL image | postgres:17.11-alpine |

Project paths: `backend/legacy` and `frontend/legacy-ui`.
Node/npm initially were absent from PATH. Validation used the existing NVM
installation by prepending `/home/ai-dev/.nvm/versions/node/v24.18.0/bin`.
The execution sandbox failed to initialize (`mountinfo path is not absolute`);
commands ran through approved escalated execution.

## Commands and results

Commands below ran in their respective project directories, with the environment
in README.md. Validation used a generated temporary password, never logged.

| Command/check | Result |
| --- | --- |
| Initial `./mvnw test`, without datasource configuration | Failed: 1 test, 1 error; JPA could not configure a datasource |
| Initial `npm run build`, before loading Node PATH | Could not run: npm absent from PATH |
| `docker compose config --quiet` | Passed |
| `docker compose up -d --wait postgres` | Passed; new empty volume, healthy PostgreSQL |
| `./mvnw test`, with PostgreSQL datasource environment | Passed: 1 test, 0 failures, 0 errors, 0 skipped |
| `./mvnw package`, same environment | Passed: executable JAR built; same 1 test passed |
| `npm ci` | Passed: 359 packages installed, audit reported 0 vulnerabilities |
| `npm run build` after `npm ci` | Passed: TypeScript and production prerendering, `/` and `/_not-found` |
| `npm run lint` | Passed |
| `java -jar target/legacy-0.0.1-SNAPSHOT.jar --server.address=127.0.0.1 --server.port=18080` | Started successfully |
| `curl --fail http://127.0.0.1:18080/actuator/health` | HTTP 200, status `UP` |
| `npm run start -- --hostname 127.0.0.1 --port 3100` | Started successfully |
| `curl --fail http://127.0.0.1:3100` | HTTP 200; generated homepage text verified |
| `docker compose ps` | Healthy, published only at `127.0.0.1:54329` |
| PostgreSQL public table count | 0; no application schema created |
| `git check-ignore` using root rules in a temporary Git repository | target, node_modules, .next, .env, next-env.d.ts ignored; README, Compose, wrapper properties, package-lock retained |
| Frontend `git status --short` and tracked-output check | Clean; no tracked .next, node_modules, out or build files |
| `docker compose down --volumes` | Removed only this validation's new Compose service, network and volume |

Both smoke servers were stopped. The temporary password file was removed.
There is no frontend test suite in the generated scaffold (0 frontend unit tests).
Migration result: not applicable; Flyway/schema migrations belong to Slice 1.
Manual smoke was HTTP-based; no browser visual inspection was performed.

## Acceptance and limitations

- Backend tests: passed with the documented PostgreSQL prerequisite; no test
  exclusions, mocked database, or weakened context test.
- Frontend production build: passed after a lockfile-based dependency install.
- Generated output: root ignore rules validated; existing frontend Git history
  contains no generated build output. Root tracking cannot be checked until
  a root repository exists.
- Clean committed root baseline: pending. The smallest next step is explicit
  authorization to consolidate root Git tracking while preserving the nested
  frontend history, then create the baseline commit.
- The generated Google font loader requires network access during a fresh build.
- npm reported a blocked optional `unrs-resolver` postinstall script; build and
  lint succeeded without changing script policy. Java emitted the generated
  test stack's Mockito dynamic-agent warning; the test passed.
- No analysis accuracy, scan consistency, migration, or MCP acceptance is claimed.
