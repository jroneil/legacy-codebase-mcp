# Legacy Codebase MCP Server

Deterministic code intelligence for legacy Struts/Grails applications. Slice 2
adds a Java symbol index and evidence-backed relationship queries to the
immutable scan foundation. Framework analysis, MCP, and the explorer follow in
later slices. The frontend remains the generated Next.js scaffold.

Scope and acceptance criteria: [PRD](docs/PRD.md) and
[implementation plan](docs/IMPLEMENTATION_PLAN.md).

## Prerequisites

- Java 21; Maven wrapper at `backend/legacy/mvnw` downloads Maven 3.9.16.
- Docker with Docker Compose. Backend tests use disposable PostgreSQL
  Testcontainers and require a working Docker daemon; no test database setup
  or datasource credentials are needed.
- Node.js 24 and npm for the frontend (validated with Node 24.18.0 / npm 12.1.0).
  With NVM, load NVM and select Node 24 in the current shell.

## Local database and backend

From the repository root, configure a password without putting it in shell
history and start PostgreSQL:

```bash
read -rsp 'Local PostgreSQL password: ' POSTGRES_PASSWORD
export POSTGRES_PASSWORD
export POSTGRES_PORT=54329
export SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:${POSTGRES_PORT}/legacy"
export SPRING_DATASOURCE_USERNAME=legacy
export SPRING_DATASOURCE_PASSWORD="$POSTGRES_PASSWORD"
export LEGACY_REPOSITORY_ROOT=/absolute/path/to/legacy/source
export ANALYZER_VERSION=java-symbol-index-2

docker compose up -d --wait postgres
cd backend/legacy
./mvnw test
./mvnw package
./mvnw spring-boot:run
```

Flyway automatically applies `V1__scan_foundation.sql` and `V2__java_symbol_index.sql`. Hibernate is configured
with `ddl-auto=validate`, never create/update. Persistence uses JDBC, so migration
and integration tests validate its relational schema; there are no JPA entity
mappings yet. Remove any old `SPRING_JPA_HIBERNATE_DDL_AUTO` override from Slice 0.
Check `http://127.0.0.1:8080/actuator/health` for `UP`.

The backend and PostgreSQL bind only to loopback by default. Override
`POSTGRES_PORT` before setting the datasource URL if the port is occupied.
Credentials are externalized. The password initializes a new volume only;
use the existing password when restarting an initialized database.
From the root, `docker compose stop` preserves data. Do not remove an existing
volume unless you intend to delete its scan history.

## Scan API

`POST /api/scans` synchronously inventories the configured root. No target path
is accepted from the request. An unset root returns 503 without creating a
scan. `ANALYZER_VERSION` defaults to `java-symbol-index-2` and can be overridden.

```bash
curl -i -X POST http://127.0.0.1:8080/api/scans
curl 'http://127.0.0.1:8080/api/scans?limit=20&offset=0'
curl 'http://127.0.0.1:8080/api/scans/REPLACE_WITH_SCAN_UUID?limit=100&offset=0'
```

- POST returns 201 with `Location` and the resulting scan detail. Inspect
  `scan.status`: a created scan may finish `COMPLETED` or `FAILED`.
- List returns `activeScanId` and a page of scan metadata, newest first.
- Detail returns `scan`, `active`, and separate `files`/`errors` pages.
  For PENDING, RUNNING, or FAILED scans, inventory is never returned.
- Each page has `items`, `totalCount`, `offset`, `limit`, and `truncated`.
  Default limit is 100; maximum is 200. Offsets range from 0 to 1,000,000.
  Detail applies the same limit/offset independently to files and errors.
- Unknown scan UUIDs return 404; malformed IDs and invalid page bounds return 400.

Lifecycle: `PENDING -> RUNNING -> COMPLETED` (then active), or
`PENDING -> RUNNING -> FAILED`. Collection occurs outside database transactions.
A single publication transaction inserts all inventory/errors, completes the
scan, and switches the active pointer. Concurrent publications serialize on
that pointer; the last successful publication becomes active. Query services
read a consistent database snapshot. Failure rolls back publication and leaves
the previous active scan available. Historical completed scans are immutable.
Each scan has its own file set, so deletions disappear in the next snapshot.

## Inventory behavior and boundaries

Supported extensions: `.java`, `.groovy`, `.xml`, `.sql`, `.jsp`, `.gsp`,
`.properties` (case-insensitive). These are file categories, not semantic
parsers. Malformed Java/XML/SQL is still valid inventory input.

- Ignore `.git`, `target`, `build`, `.gradle`, and `node_modules` at every depth.
- Skip all symlinks, including links within the root, and non-regular files.
  Symlinked root paths or parent components are rejected. Use a physical root.
  Secure directory-handle operations prevent symlink replacement from escaping
  the root. A filesystem without Java `SecureDirectoryStream` support fails
  closed; this baseline is validated on Linux.
- Store relative paths, category, SHA-256 of original bytes, size and encoding.
  Relative path is the stable file identity within a configured repository;
  files are unique by `(scan_id, relative_path)`.
- Validate UTF-8; recognize BOM-marked UTF-16LE/BE. Invalid encoding retains the
  file/hash with encoding `UNKNOWN` and an `UNRESOLVED` error. NUL-bearing files
  without a recognized UTF-16 BOM are treated as binary and omitted.
- Record localized read/encoding errors with relative path, stage, code and
  safe message. Root access failure fails the scan. Source content and raw
  exception messages are never stored or returned by this slice.
- Read ordinary `.git/HEAD`, loose refs and packed refs without running Git or
  target code. A non-Git repository or unborn branch has a null SHA. External
  worktree/submodule metadata is not followed; it produces a Git metadata error.
  Packed refs are bounded at 4 MiB; oversized metadata also produces an error.
- Files are opened read-only. Byte contents and modification times are not
  changed by the scanner. No target build or application is executed.

This is a full synchronous scan, not a background job or filesystem snapshot.
Keep source stable while scanning: ordinary per-file changes are detected and
omitted with an error, but the recorded Git SHA does not certify a clean tree
or an atomic checkout. Metadata is held in memory until publication; contents
are streamed. There is one configured repository and one global active pointer.
An abrupt process/database failure may leave a PENDING/RUNNING record; it is
never active and a new POST can proceed. Automatic recovery, retention cleanup,
custom ignores, incremental scans, and broader encoding detection are not
implemented in this slice.

## Java symbol index (Slice 2)

JavaParser core and Symbol Solver 3.28.2 run with Java 21 language configuration.
Only securely reread, inventory-hash-verified Java files enter an in-memory source
solver; it handles Maven module roots and nonstandard layouts through declared
package/type names. The JDK is the only external classpath. Target builds,
build-tool execution, dependency downloads, and target class loading never occur.
Missing third-party jars/types degrade affected references without aborting scans.

Indexed declarations: packages, classes/interfaces (including member classes),
methods and fields. Stable IDs are:

```text
java:package:com.acme
java:type:com.acme.Service
java:method:com.acme.Service#find(java.lang.Long)
java:field:com.acme.Service#repository
```

Method signatures use qualified erased parameter types and arrays for varargs.
An unresolved type is prefixed with `?`, for example `find(?MissingType)`; its
symbol identity state is UNRESOLVED. These IDs repeat for identical input and
classpath, but can improve when missing type information becomes available.
Package locations represent the first declaration in sorted source-path order.

Relationships are `CONTAINS`, `IMPORTS`, `EXTENDS`, `IMPLEMENTS`, `OVERRIDES`, and
basic method `CALLS`, with source path, line/column, evidence type and resolution:

- RESOLVED: declaration syntax or static symbol resolution establishes the edge.
  Calls identify the compile-time declaration, not a guaranteed runtime receiver.
- INFERRED: an `@Override` annotation expresses override intent but no ancestor
  method could be confirmed; no concrete target is invented.
- UNRESOLVED: missing/ambiguous references, including unsupported static/wildcard
  import targets. Wildcard imports can still assist resolution of ordinary calls.

External JDK targets have a resolved target description but no indexed target ID.
Malformed Java is omitted from semantic indexing with a file-level error; its
inventory remains. Duplicate qualified declarations are rejected with diagnostics.
Unsupported enum/record/annotation/local types are omitted with diagnostics;
anonymous bodies, constructors, method references and object-creation calls are
not indexed. Reflection and dynamic dispatch are not analyzed. Java files over
8 MiB, changed files, unreadable files, or unknown encodings retain inventory
but receive an analysis error. ASTs are held in memory for the scan.

Symbols and relationships publish in the same transaction as files and the
active pointer. All symbol queries use only the active completed scan and
return freshness metadata. Existing Slice 1 snapshots remain valid but contain
no symbols until a new scan runs. No source bodies, argument literals, or raw
parser exceptions are returned.

```bash
curl 'http://127.0.0.1:8080/api/symbols/search?q=Service&limit=20'
curl 'http://127.0.0.1:8080/api/symbols/java:type:com.acme.Service'
curl 'http://127.0.0.1:8080/api/symbols/java:method:com.acme.Service%23find(java.lang.Long)/usages'
```

Percent-encode stable IDs in URL paths, especially `#`, `?`, and array brackets.
Search returns all matching candidates (literal case-insensitive substring of
simple/qualified names, or exact stable ID), never a selected ambiguous match.
Detail returns a symbol and outgoing relationships; usages return incoming
relationships excluding structural CONTAINS edges. Limits are 1..200 (default
100), offsets 0..1,000,000, with total counts and truncation flags. A blank search
lists symbols; no active scan returns an empty search with null freshness.
Missing symbols or detail/usages without an active scan return 404.

## Frontend

```bash
cd frontend/legacy-ui
npm ci
npm run build
npm run start -- --hostname 127.0.0.1
```

Open `http://127.0.0.1:3000` to see the generated page. Google font loading needs
network access during a fresh build. `npm run lint` is available; no frontend
features or frontend test suite have been added.

## Validation and version control

See [Slice 2 validation](docs/validation/SLICE_02_VALIDATION.md),
[Slice 1 validation](docs/validation/SLICE_01_VALIDATION.md), and the earlier
[Slice 0 record](docs/validation/SLICE_00_VALIDATION.md).
The supplied workspace has a frontend-only Git repository; no root commit SHA
is available. Changes are uncommitted. Root ignore rules exclude generated
output and local credentials.
