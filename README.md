# Legacy Codebase MCP Server

Deterministic, evidence-backed code intelligence for legacy Struts and Grails
applications, exposed to coding agents over MCP and to people over REST and a small
web explorer with an explicit scan control.

## Why this project exists

Coding agents are good at interpretation and slow at repetitive archaeology. Answering
"which URL reaches this action?", "what service does it call?", "which tables can that
path read or write?" means reading dozens of Java, XML, Groovy and JSP files before any
real work starts — and repeating it for the next question.

This server does that discovery once, deterministically, and stores the result:

- **Repetitive codebase discovery moves out of the LLM and into indexed tooling.** The
  analyzer walks the repository, parses sources and configuration, and builds a
  normalized model of symbols, framework wiring, dependency paths and database usage.
- **The analysis is repeatable and cited.** Every relationship carries `RESOLVED`,
  `INFERRED` or `UNRESOLVED` state plus source file/line evidence, and identical input
  produces identical output. Uncertainty is preserved rather than guessed away.
- **The agent consumes structured evidence instead of rediscovering it.** MCP tools
  (`search_symbols`, `get_symbol`, `find_usages`, `trace_component`,
  `list_database_tables`, `find_table_usages`, `inspect_location`, `list_entry_points`)
  return bounded, evidence-backed answers, so the model's reasoning goes into
  interpretation. REST exposes the same services, and the explorer shows the same
  snapshot.
- **No LLM API is required inside the application.** Core analysis is local and static:
  no model calls, no embeddings, no source code sent anywhere. You bring the agent; this
  server supplies the evidence.

Scope and acceptance criteria: [PRD](docs/PRD.md) and
[implementation plan](docs/IMPLEMENTATION_PLAN.md). What is proven, and what is not, is
recorded in the [validation records](docs/validation/) — including
[real-repository validation](docs/validation/REAL_REPOSITORY_VALIDATION.md).

## Quick start

Requirements: Docker with Docker Compose, and a host directory containing one or more
legacy repositories as immediate child directories.

```bash
git clone <this-repository> legacy-codebase-mcp
cd legacy-codebase-mcp
cp .env.example .env
# edit .env: set POSTGRES_PASSWORD and, if needed, LEGACY_REPOSITORY_BASE
docker compose up --build
```

`LEGACY_REPOSITORY_BASE` defaults to this project directory. For a separate collection,
set it to a physical host directory such as `/srv/legacy-repositories`, with a layout like:

```text
/srv/legacy-repositories/
├── legacy-struts/
└── legacy-grails/
```

The first build compiles the backend and frontend inside Docker, so it downloads Maven
and npm dependencies and takes several minutes. Later starts reuse the images and take
seconds. The stack runs exactly three services — `postgres`, `backend` and `frontend` —
and Compose starts them in order: the backend waits for healthy PostgreSQL, then the
frontend waits for a healthy backend.

| What | URL |
| --- | --- |
| Explorer UI | http://127.0.0.1:3000 |
| REST API | http://127.0.0.1:8080 |
| MCP endpoint | http://127.0.0.1:8080/mcp |
| Backend health | http://127.0.0.1:8080/actuator/health |

All host ports bind to loopback only, and PostgreSQL is not published to the host.

### Required `.env` values

| Variable | Meaning |
| --- | --- |
| `POSTGRES_PASSWORD` | Any local password. It initializes a new PostgreSQL volume; changing it later does not change an existing volume. |

`LEGACY_REPOSITORY_BASE=.` is optional and defaults to the Compose project directory.
It is the host directory whose immediate, visible child directories appear in the scan
selector. Compose mounts the base read-only at `/workspace/repos`; symlinked bases and
repository entries are rejected by the backend.

Other optional values, with defaults shown: `POSTGRES_DB=legacy`,
`POSTGRES_USER=legacy`, `ANALYZER_VERSION=grails-index-5` (recorded with each snapshot as
freshness metadata), `BACKEND_PORT=8080`, `FRONTEND_PORT=3000`, and
`POSTGRES_PORT=54329` (used only by the native workflow below). `.env` is gitignored;
never commit it.

## Create a scan

Open http://127.0.0.1:3000/scan, select a discovered repository, and choose **Scan**.
The page reports the result and retains scan history. The equivalent REST flow is:

```bash
curl -s http://127.0.0.1:8080/api/repositories
curl -s --max-time 0 -X POST http://127.0.0.1:8080/api/scans \
  -H 'Content-Type: application/json' \
  -d '{"repository":"legacy-struts"}' | tee scan.json
python3 -c "import json;s=json.load(open('scan.json'))['scan'];print(s['repositoryRoot'],s['status'],s['fileCount'],s['errorCount'])"
```

`POST /api/scans` is synchronous: the response arrives when the scan finishes, and a
large repository can take minutes. Progress is not streamed. A failed scan leaves the
previous active snapshot in place. An invalid selection returns 400 without creating a
scan; an unavailable repository base returns 503. See [Scan API](#scan-api) for the full
contract.

Then explore the active snapshot:

```bash
curl -s 'http://127.0.0.1:8080/api/symbols/search?q=Customer&limit=20'
curl -s --get 'http://127.0.0.1:8080/api/relationships/trace' --data-urlencode 'component=/customer/search'
curl -s --get 'http://127.0.0.1:8080/api/relationships/database-tables' --data-urlencode 'component=/customer/search'
curl -s --get 'http://127.0.0.1:8080/api/entry-points' --data-urlencode 'path=/customer/search'
```

Point an MCP client at http://127.0.0.1:8080/mcp (see
[MCP interface](#mcp-interface-slice-6)).

## Add or select another repository

Add another physical directory directly under `LEGACY_REPOSITORY_BASE`, then reload the
scan page and select it. The backend container does not need to be recreated. If you
change the base directory itself, update `.env` and recreate the backend so Docker can
replace the bind mount:

```bash
# edit LEGACY_REPOSITORY_BASE in .env
docker compose up -d --force-recreate backend
```

Each successful scan creates a new immutable snapshot and becomes the one global active
snapshot. Older completed scan metadata, inventories, symbols, relationships, and errors
remain stored by scan ID. Each snapshot records its selected in-container root, for
example `/workspace/repos/legacy-struts`, plus the repository Git SHA when available.

## Stop, start, and reset

```bash
docker compose stop            # stop the stack; keep the database volume and scan history
docker compose start           # start it again against the same data
docker compose down            # remove containers and network; keep the database volume
docker compose up -d --wait    # start again after down; earlier scans are still there
docker compose down --volumes  # DESTRUCTIVE: delete the PostgreSQL volume and all scan history
docker compose ps              # service status and health
docker compose logs -f backend # follow backend logs
docker compose exec postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"' # exit with \q
```

## Security boundary

- The configured repository base is mounted **read-only**; the analyzer never writes to
  it and never builds or executes target code.
- PostgreSQL has no host port. Only the backend reaches it, over the Compose network.
- The UI, REST and MCP host ports bind to `127.0.0.1` only, so MCP stays local.
- Credentials live in `.env`, which is gitignored. Nothing secret is committed.
- Analysis is static and local; no source leaves the machine.

## Container images

| Image | Base | Notes |
| --- | --- | --- |
| backend (`backend/legacy/Dockerfile`) | `maven:3.9-eclipse-temurin-21` build → `eclipse-temurin:21-jre-jammy` runtime | Multi-stage. The build stage compiles the Spring Boot jar on Java 21 and skips tests (they need a Docker daemon); the runtime image is a Java 21 JRE with no Maven, running as a non-root `app` user. |
| frontend (`frontend/legacy-ui/Dockerfile`) | `node:24-alpine` for dependencies, build and runtime | Multi-stage. Next.js `output: "standalone"`; the runtime stage copies `server.js` and static assets only, running as a non-root `nextjs` user. |

The backend runtime image is deliberately the **glibc (Jammy)** Temurin build rather than
Alpine. The Alpine/musl Temurin build did not provide a usable
`java.nio.file.SecureDirectoryStream` anywhere in the validated container environment, and
`RepositoryInventory` refuses to inventory a repository without a secure directory handle:
scans failed closed with `FAILED` and zero files. Switching to Jammy made secure handles
available again and preserved the existing secure-filesystem guarantee, so no analyzer
behavior had to be relaxed.

Wire-up inside the Compose network:

- the backend reaches PostgreSQL at `jdbc:postgresql://postgres:5432/<POSTGRES_DB>`
  (`legacy` by default), using the Compose service hostname and never a host port;
- the frontend reaches the backend at `LEGACY_API_BASE_URL=http://backend:8080` for its
  server-side requests;
- the backend listens on `0.0.0.0:8080` *inside* its container so the loopback-only host
  port mapping and the frontend container can reach it.

## Native developer workflow (contributors)

For changing the analyzer itself, run the pieces on the host. This keeps fast Java
iteration and the frontend test suite available. It needs Java 21 (the Maven wrapper at
`backend/legacy/mvnw` downloads Maven 3.9.16), Node.js 24 with npm, and a working Docker
daemon for the database and for backend tests (Testcontainers).

```bash
# 1. PostgreSQL on loopback. The default Compose stack does not publish it; use the
#    development override only when a host PostgreSQL port is needed.
export POSTGRES_PASSWORD=local-dev-only
export POSTGRES_DB=legacy
export POSTGRES_USER=legacy
export POSTGRES_PORT=54329
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --wait postgres

# 2. Backend on the host.
export SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:${POSTGRES_PORT}/${POSTGRES_DB}"
export SPRING_DATASOURCE_USERNAME="$POSTGRES_USER"
export SPRING_DATASOURCE_PASSWORD="$POSTGRES_PASSWORD"
export LEGACY_REPOSITORY_BASE=/absolute/path/containing/legacy-repositories
cd backend/legacy
./mvnw spring-boot:run

# 3. Frontend on the host (optional).
cd frontend/legacy-ui
npm ci
LEGACY_API_BASE_URL=http://127.0.0.1:8080 npm run dev
```

`./mvnw spring-boot:run` compiles and runs the backend directly, so `./mvnw package` is
not required first. The full backend suite (`cd backend/legacy && ./mvnw test`) and the
frontend tests and build (`cd frontend/legacy-ui && npm test && npm run build`) use
disposable resources and need no manual database setup. For a host-run backend the
Dockerfile is not involved: `SERVER_ADDRESS` is unset, and the application's configured
default binds to `127.0.0.1`.

To build the images without starting them:

```bash
docker compose build
```

## Scan API

`GET /api/repositories` returns at most 200 immediate, visible child directories of the
configured repository base in deterministic name order, with `totalCount` and
`truncated`. It never exposes arbitrary host paths. Hidden entries, files, and symlinks
are omitted.

`POST /api/scans` synchronously inventories one repository selected by its discovered
ID. The JSON request is `{"repository":"legacy-struts"}`. Absolute paths, nested paths,
`..`, hidden names, missing entries, non-directories, symlinks, and roots that escape the
configured base are rejected with 400 before a scan row is created. An unavailable or
unsafe base returns 503. `ANALYZER_VERSION` defaults to `grails-index-5` and can be
overridden. Large repositories take minutes, so allow a long client timeout.

```bash
curl 'http://127.0.0.1:8080/api/repositories'
curl -i --max-time 0 -X POST http://127.0.0.1:8080/api/scans \
  -H 'Content-Type: application/json' \
  -d '{"repository":"legacy-struts"}'
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
are streamed. A fixed repository base can contain multiple selectable repositories;
there is one global active pointer.
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

## Struts 1 and Spring XML wiring (Slice 3)

The scanner discovers `web.xml`, `struts-config*.xml`, `applicationContext*.xml`,
and explicitly referenced/imported XML. JDK SAX parsing uses inventory-verified,
read-only bytes. External DTD/schema access and entity declarations are disabled;
legacy external DOCTYPE declarations do not trigger downloads. Malformed XML,
missing imports/classes and import cycles produce localized errors. Limits are
8 MiB per XML file, 20,000 elements, nesting 100, and 100 imported files per context.

Web servlet/configuration mappings connect to Struts routes and indexed Action
classes. Form-beans connect to Java ActionForms. Named local/global forwards
retain evidence; local forward names override global names. `RENDERS` identifies
configured views, while `FORWARDS_TO` identifies another action. Missing or
ambiguous destinations retain unresolved descriptions. Forward query strings
and external URL credentials are not returned.

DispatchAction's `parameter` is a request parameter **key**, not a literal method
name. Matching four-argument methods are conditional `INFERRED` candidates with
`request parameter key=method` evidence. Dynamic request values and method access
checks are not available in the existing Java symbol model; no runtime method
selection is claimed.

Spring beans are scoped to a root XML file plus its imports. Direct property and
constructor `ref`/`<ref>` bindings produce `INJECTS`; beans connect to indexed
implementation classes with `WIRES_TO`. Setter parameter types and explicit
constructor `type` attributes establish scoped interface-to-implementation
bindings when Java inheritance confirms compatibility. `autowire="byType"`
uses indexed setter types: a unique candidate is `INFERRED`, while multiple or
missing candidates are `UNRESOLVED`. Explicit references take precedence.
Duplicate bean IDs retain candidates rather than applying runtime override order.

Stable IDs include the configuration scope, for example:

```text
struts:route:web/WEB-INF/struts-config.xml#/customer/search
struts:form:web/WEB-INF/struts-config.xml#customerForm
spring:bean:web/WEB-INF/applicationContext.xml#customerService
web:view:web/WEB-INF/views/search.jsp
```

Duplicate declarations receive source-location suffixes. Framework evidence uses
the existing immutable scan tables and publishes atomically with Java evidence.
Old snapshots are unchanged; rescan to obtain framework records.

```bash
curl --get 'http://127.0.0.1:8080/api/entry-points' --data-urlencode 'path=/customer/search'
curl --get 'http://127.0.0.1:8080/api/entry-points/trace' \
  --data-urlencode 'entryId=struts:route:web/WEB-INF/struts-config.xml#/customer/search'
curl --get 'http://127.0.0.1:8080/api/symbols/detail' \
  --data-urlencode 'stableId=spring:bean:web/WEB-INF/applicationContext.xml#customerService'
```

Entry points return all exact-path candidates, with freshness and pagination
(limit 1..200, offset 0..1,000,000). Select a stable `entryId` for tracing. Trace
`components` project the route and Java implementations; `evidence` retains the
intermediate bean bindings with per-edge locations and states. The fixture gives
`/customer/search -> CustomerAction -> CustomerServiceImpl -> CustomerDAOImpl`,
with the `CustomerService` interface binding available through symbol detail.
A class-name match between an Action and a Spring bean is `INFERRED`: runtime
Struts/Spring delegation is not established. The weakest evidence determines
trace confidence. Independent bean contexts are never combined by simple name.

Traces follow configuration injection only, stop at cycles or unresolved edges,
and report termination and `truncated`. Bounds: depth 0..8 (default 8), paths
1..100 (default 100), 200 edges per expansion and 1,000 total edges. A bounded
result is not a complete dependency tree. No active snapshot yields empty entry
points and a 404 for traces. Invalid bounds return 400. Symbol detail/usages also
accept query parameters (`/api/symbols/detail?stableId=...` and
`/api/symbols/usages?stableId=...`) for IDs containing slashes.

Initial limitations: no runtime application execution, annotation/component scan,
Spring profile/parent/factory evaluation, collection/inner-bean injection, alias
elements, autowire modes other than `byType`, wildcard imports, or merged context
hierarchies. Factory/abstract beans do not establish runtime class bindings.
Unsupported nested injection is unresolved. Source-only Java/classpath limits
remain. Database evidence and general relationship queries are described below.

## Database usage (Slice 4)

JSqlParser **5.4** classifies SQL extracted from Java ASTs. Supported sources:
JDBC Statement execution, Connection prepareStatement/prepareCall and subsequent
prepared execution; common JdbcTemplate/NamedParameterJdbcTemplate string-first
calls; SQL declarations/constants, including final fields, explicit static imports
and unchanged locals; Hibernate `*.hbm.xml`, basic HQL and named/native queries.
Only inventory-hash-verified bytes are read. No target application, build, SQL,
procedure, Hibernate session or database connection is executed.

`DatabaseTable` and `QueryArtifact` use the existing scan-scoped symbol storage
with kinds `DATABASE_TABLE` and `QUERY_ARTIFACT`. Stable identities are:

```text
db:table:CUSTOMER
db:table:app."Customer"
db:query:src/demo/CustomerDAO.java#10:32
```

Table identity preserves spelling, qualification and quoting; it does not assume
a database dialect, default schema or datasource. These are source references,
not confirmation that a live table exists. Query IDs use the declaration/call's
file, line and column and repeat for identical inputs. Query `signature` contains
JSON metadata: language, statement type, parse status, sanitized source expression,
SQL template, partial literals and procedure name where known. Quoted literal values and
comments are hidden; quoted identifiers are hidden in previews but retained in
parsed table identities. Metadata previews are bounded to 4,000 characters.

Direct relationships:

- `DECLARES_QUERY`: source declares SQL, prepares a statement, or obtains a query.
- `EXECUTES_QUERY`: an indexed execution call refers to that artifact. Preparing
  a statement alone does not establish execution. `addBatch` records declaration.
- `READS_TABLE` / `WRITES_TABLE`: query artifact to explicit SQL tables.
- `MAPS_TO_TABLE`: mapped Java class (or XML context if class is missing) to an
  explicit Hibernate table. Mapping alone does not imply reads or writes.

| SQL | Direct access |
| --- | --- |
| SELECT, including SELECT…FOR UPDATE | READS_TABLE; locking does not imply writing |
| INSERT | WRITES_TABLE destination |
| UPDATE / DELETE | WRITES_TABLE destination; explicit subqueries add reads |
| MERGE | WRITES_TABLE destination; USING sources add reads |
| INSERT…SELECT | WRITES_TABLE destination and READS_TABLE sources, even if identical |
| CALL / JDBC procedure escape | Procedure name retained; body/table effects unknown |

Aliases and scoped CTE names are excluded from physical table records. SQL parse
failures and unsupported write extensions retain unresolved artifacts and safe
location diagnostics, with no guessed tables. There is no SQL fallback heuristic.
Fully constant concatenation is folded; unknown/reassigned/shadowed expressions
remain unresolved with partial templates and expression evidence.

Hibernate XML supports explicit class/secondary/collection table declarations,
root/class schema and catalog attributes, and named `<query>` / `<sql-query>`
artifacts. Basic single-entity HQL SELECT/FROM, optionally with a simple predicate,
uses explicit primary-table mappings and is always **INFERRED**. Ambiguous/missing
entity or named-query references remain unresolved. Missing framework jars mean
JdbcTemplate/Hibernate execution recognition is **INFERRED** from declared types;
SQL AST table edges remain independently parser-derived.

Existing bounded symbol APIs inspect this direct evidence:

```bash
curl --get 'http://127.0.0.1:8080/api/symbols/search' --data-urlencode 'q=db:table:CUSTOMER'
curl --get 'http://127.0.0.1:8080/api/symbols/usages' --data-urlencode 'stableId=db:table:CUSTOMER'
curl --get 'http://127.0.0.1:8080/api/symbols/detail' \
  --data-urlencode 'stableId=db:query:src/demo/CustomerDAO.java#10:32'
```

Database evidence publishes in the same transaction as files, Java/framework
symbols and the active pointer. Deleted evidence disappears on a successful
rescan. Failed/staging scans cannot replace or leak into the active model.
The existing Slice 3 trace endpoint remains configuration-only. Slice 5 adds
general dependency and database-impact endpoints below.

Limits: source/XML rereads retain the existing 8 MiB bound; SQL text is limited
to 65,536 characters with a one-second parser timeout and nesting bound 100.
Constant evaluation is bounded to depth 32. No general data-flow analysis,
StringBuilder reconstruction, target method execution, inherited/custom JDBC
wrappers, callback/batch-array analysis, standalone SQL scripts, advanced HQL,
Hibernate annotations/inheritance/naming strategies, quoted/dynamic procedure names, procedure bodies/triggers,
or database catalogue/datasource discovery. Unsupported syntax remains visible
through artifacts/diagnostics rather than being presented as resolved access.

## Relationship traversal and path confidence (Slice 5)

Read-only APIs use one completed active snapshot and return freshness metadata:

```bash
curl --get 'http://127.0.0.1:8080/api/relationships/trace' \
  --data-urlencode 'component=/customer/search'
curl --get 'http://127.0.0.1:8080/api/relationships/trace' \
  --data-urlencode 'component=java:type:demo.CustomerDAOImpl' --data-urlencode 'direction=INCOMING'
curl --get 'http://127.0.0.1:8080/api/relationships/database-tables' \
  --data-urlencode 'component=/customer/search'
curl --get 'http://127.0.0.1:8080/api/relationships/table-usages' \
  --data-urlencode 'table=CUSTOMER'
```

Selection first matches an exact stable ID, otherwise exact qualified/simple
names (including route paths). Multiple matches return `selection=AMBIGUOUS`,
up to 100 ordered candidates, `candidatesTruncated`, and no traversal. Choose a
stable ID to disambiguate. Missing symbols/active scans return 404; invalid
inputs/bounds return 400. Table usages accept only database-table symbols.

Traversal enumerates evidence paths breadth-first, shortest first; distinct
paths to the same node/table are retained. `nodes` follow traversal direction;
`evidence` is in that traversal order but each relationship retains its original
source/target orientation, file, location, type and state. Incoming table paths
therefore start at the table. Cycle detection is per path, so a repeated node
ends that branch without discarding alternate branches. Trace results include
reachable prefixes, while `frontiers` describe leaves, cycles, external or
unresolved references, and depth cutoffs. Unresolved descriptions are evidence,
never guessed nodes. `frontierSymbols` exposes terminal symbol kinds/states,
including unresolved dynamic-SQL artifacts; an empty table list is not proof
that a component has no database effects.

Path confidence uses edges only: all RESOLVED → RESOLVED; any INFERRED without
UNRESOLVED → INFERRED; any UNRESOLVED → UNRESOLVED. The schema prohibits concrete
targets on unresolved edges, so those paths stop rather than inventing a table.
A degraded terminal symbol is also reported independently of edge confidence.

`tables` returns a separate result per evidence path and access: READ, WRITE,
or MAPPING. Mapping does not imply a read/write. `direct=true` means exactly one
table relationship; all longer chains are transitive, including
method → query artifact → table. Every result contains its full supporting path
and confidence; the same table can appear multiple times with different access,
directness, paths or confidence. No strongest-path aggregation hides alternatives.

Database queries follow CONTAINS, CALLS, ROUTES_TO, FORWARDS_TO, INJECTS, WIRES_TO,
DECLARES_QUERY, EXECUTES_QUERY, READS_TABLE, WRITES_TABLE and MAPS_TO_TABLE. These
are static associations: containment includes member evidence, and declarations
retain SQL even if execution is unproven. Inspect DECLARES_QUERY versus
EXECUTES_QUERY in the chain. Imports/inheritance are not database-impact hops.
Context-specific interface WIRES_TO bindings are excluded from database walks;
bean INJECTS → WIRES_TO paths preserve the indexed XML configuration instead.
Generic trace exposes all indexed edge types, including scoped bindings with
their original descriptions; it does not establish runtime dispatch/execution.

All endpoints accept `depth` 0..16 (default 12), `limit` 1..500 (default 100), and
`fanOut` 1..200 (default 50). Each path has at most `depth` edges. Results and
frontiers each have at most `limit` paths; tables refer to the selected result
paths. Adjacency reads use LIMIT with one look-ahead row and a fixed 5,000-unit
graph-work budget counting queries and fetched rows. Terminal-symbol lookups are
separately bounded by the frontier limit. The response includes applied bounds,
expanded/examined counts, `truncated`, and ordered reasons: DEPTH_LIMIT,
FAN_OUT_LIMIT, RESULT_LIMIT, WORK_LIMIT or FRONTIER_LIMIT. Cycles alone are not
truncation. Shortest-first means within the explored, bounded graph; truncated
results are not exhaustive. Raise the applicable limits or select a downstream
stable ID for a narrower query; there is no continuation cursor in this slice.

No schema, parser, analyzer-version or dependency changes are required. No target
code executes. Existing scan publication and immutable evidence remain unchanged.

## MCP interface (Slice 6)

The backend embeds an MCP server over **Streamable HTTP** on the existing
servlet stack. It is enabled by `spring.ai.mcp.server.protocol=STREAMABLE` and
is exposed by the default Compose stack at `http://127.0.0.1:8080/mcp`. The container
process listens on `0.0.0.0:8080` so other Compose services can reach it, while its host
port remains loopback-only. The implementation is
`org.springframework.ai:spring-ai-starter-mcp-server-webmvc:2.0.1`,
which pins Spring Boot 4.1.1 and the official MCP Java SDK 2.0.0.

Every tool is a thin adapter over the same services the REST controllers use:

| MCP tool | Backing service (same as REST) |
| --- | --- |
| `search_symbols` | `SymbolStore.search` (`GET /api/symbols/search`) |
| `get_symbol` | `SymbolStore.detail` (`GET /api/symbols/detail`) |
| `find_usages` | `SymbolStore.usages` (`GET /api/symbols/usages`) |
| `trace_component` | `TraversalQueries.query(TRACE)` (`GET /api/relationships/trace`) |
| `list_database_tables` | `SymbolStore.tables` (same store/freshness as the symbol APIs) |
| `find_table_usages` | `TraversalQueries.query(TABLE_USAGES)` (`GET /api/relationships/table-usages`) |
| `inspect_location` | `SymbolStore.locate` + `SymbolStore.detail`/`usages` |
| `list_entry_points` | `FrameworkQueries.entries` (`GET /api/entry-points`) |

Results are bounded and deterministic. List tools accept `limit` (1..200,
default 50) and an opaque `cursor`, and return `returnedCount`, `totalCount`,
`truncated` and `nextCursor`. Traversal tools accept `maxDepth` (0..16),
`limit` (1..500) and `fanOut` (1..200), and return the applied bounds plus
`truncated` and ordered `truncationReasons`. Every response carries scan
freshness (`scanId`, `gitCommitSha`, `analyzerVersion`, `scanCompletedAt`) from
the active completed snapshot, or `null` when no scan is active. Resolution
states, ambiguity candidates, weakest-edge path confidence and evidence
locations are passed through unchanged; no source file content or raw SQL/XML
is returned. Tool errors (no active scan, unknown stable ID, invalid bounds or
`inspect_location` line) are reported in the `error` field rather than as
protocol failures.

Local client configuration for a coding agent (Codex CLI, `~/.codex/config.toml`):

```toml
[mcp_servers.legacy-codebase]
url = "http://127.0.0.1:8080/mcp"
enabled = true
```

Any Streamable HTTP MCP client can use the same URL. The endpoint exposes
source-derived code intelligence to the connected agent; keep it on loopback
and treat that boundary as documented in `AGENTS.md`.

## Frontend (Slice 7)

The Next.js App Router UI is an inspection interface over the REST API with one
operational scan control. It contains no analysis logic: every relationship, resolution
state, access kind,
directness and truncation flag is displayed exactly as the backend returns it.

In the Compose stack it runs at http://127.0.0.1:3000 and reaches the backend over the
Compose network (`LEGACY_API_BASE_URL=http://backend:8080`). To run it on the host
instead, as in the [native workflow](#native-developer-workflow-contributors):

```bash
cd frontend/legacy-ui
npm ci
npm test          # vitest, server-rendered component/page tests
npm run build
LEGACY_API_BASE_URL=http://127.0.0.1:8080 npm run start -- --hostname 127.0.0.1
```

`LEGACY_API_BASE_URL` is server-side only and defaults to `http://127.0.0.1:8080`.
Google font loading needs network access during a fresh build. Routes:

| Route | Purpose |
| --- | --- |
| `/` | symbol search with candidate selection for ambiguous names |
| `/symbols/[id]` | symbol identity, outgoing relationships and incoming usages |
| `/entry-points`, `/entry-points/trace` | routes and their configuration traces |
| `/trace` | component traversal with bounds, path state, evidence and truncation |
| `/tables`, `/tables/[id]` | table impact (READ/WRITE/MAPPING, direct vs transitive) and table usages |
| `/scan` | repository selector, scan trigger, result, active freshness and history |
| `/errors` | localized analysis errors for a scan |

Pages render on demand (`force-dynamic`, uncached `fetch`), so a build does not need a
running backend. `npm run lint` is also available.

## Grails mapping (Slices 8, 8.1)

Groovy sources are parsed statically with the official Groovy AST
(`org.apache.groovy:groovy`, version managed by Spring Boot 4.1.1) up to the
**conversion phase only**: target code is never compiled, loaded or executed. The
supported baseline is **Grails 2.6 plus representative Grails 3.x–6.x layouts**:

- `grails-app/conf/UrlMappings.groovy` (Grails 2.6) and
  `grails-app/controllers/**/UrlMappings.groovy` (Grails 3.x+), including Grails 2.x
  named mappings (`name customerList: "/customers"(controller: …)`) and per-mapping
  constraint closures;
- `grails-app/conf/spring/resources.groovy` in both layouts;
- `grails-app/{controllers,services,domain}` and `src/groovy`, which are identical
  across those versions.

This is not generic Grails 2.x or generic Grails support.

Indexed constructs, all into the existing normalized model (no new symbol kinds,
relationship types or schema changes):

| Construct | Evidence |
| --- | --- |
| `UrlMappings.groovy` | `ROUTE` symbol `grails:route:<path>#<uri>`; `ROUTES_TO` to the controller action method |
| Controller, action, service | `CLASS`/`METHOD` symbols `groovy:type:` / `groovy:method:` |
| Service/property injection | `INJECTS` from the owning class to the service class (or candidates when ambiguous) |
| Service usage | `CALLS` to the resolved service method |
| Domain class | `CLASS` symbol plus `MAPS_TO_TABLE` to `db:table:<name>` |
| `resources.groovy` | `CONTEXT` + `BEAN` symbols with `WIRES_TO` and `INJECTS` |
| GORM dynamic finders | `READS_TABLE` (and `CALLS` to the domain) only when the domain is statically determined |

Resolution rules:

- `RESOLVED` — explicit evidence: `controller:`/`action:` in a mapping, a declared
  property type, `static mapping = { table 'X' }`, an explicit bean class in `resources.groovy`.
- `INFERRED` — documented Grails/GORM convention: the default `index` action,
  `resources:` REST mappings, `def customerService` resolving to `CustomerService`,
  the conventional snake_case table name, and GORM dynamic-finder reads.
- `UNRESOLVED` — dynamic or metaprogrammed behavior: `controller: "$controller"`
  expressions, unknown or ambiguous controllers/services/actions, missing
  dependencies, unresolved bean `ref`s, `metaClass`/`methodMissing` usage.

Dynamic finders such as `Customer.findByLastName(...)` produce an inferred
`READS_TABLE` only when the receiver resolves to an indexed domain class; arbitrary
method names never invent targets. Ambiguous references keep their candidate list.
Conventional table names follow the Grails/Hibernate physical naming rule
(`CustomerOrder` → `customer_order`). Malformed Groovy records a localized
`GROOVY`/`GROOVY_PARSE` analysis error and does not fail the scan.

Legacy Grails 2.x boundaries: named mappings keep their mapping name as route
evidence (`name=customerList; controller=customer; action=list`), a nested
per-mapping constraint closure (`id matches: /\d+/`) is not treated as a route, and
a closure action (`def list = { … }`) is **not** published as a method — its route
resolves to the controller class with a description saying the action is not
statically indexed, rather than inventing a `RESOLVED` method edge.

Example flow, reproducible from the fixture repository:

```text
/customer/$id
  -> ROUTES_TO · RESOLVED   groovy:method:demo.CustomerController#show(Long)
  -> CALLS · RESOLVED       groovy:method:demo.CustomerService#findByLastName(String)
  -> READS_TABLE · INFERRED db:table:CUSTOMER
```

Query it through the framework-neutral relationship endpoints, which traverse the
same normalized index as the Struts/Java evidence:

```bash
curl --get 'http://127.0.0.1:8080/api/relationships/trace' \
  --data-urlencode 'component=/customer/$id'
curl --get 'http://127.0.0.1:8080/api/relationships/database-tables' \
  --data-urlencode 'component=/customer/$id'
curl --get 'http://127.0.0.1:8080/api/entry-points' \
  --data-urlencode 'path=/customer/$id'
```

The entry-point trace endpoint keeps its Struts/Spring bean-bridge semantics; for a
Grails route it stops at the controller action, and the deeper service/domain/table
chain is returned by `/api/relationships/trace`.

## Validation and version control

See [Slice 10 validation](docs/validation/SLICE_10_VALIDATION.md) (runtime repository
selection), [Slice 9 validation](docs/validation/SLICE_09_VALIDATION.md)
(containerized distribution),
the [real-repository validation](docs/validation/REAL_REPOSITORY_VALIDATION.md)
(accuracy against the real `weblegacy/struts1` codebase, including the defects it found),
[Slice 8.1 validation](docs/validation/SLICE_08_1_VALIDATION.md),
[Slice 8 validation](docs/validation/SLICE_08_VALIDATION.md),
[Slice 7 validation](docs/validation/SLICE_07_VALIDATION.md),
[Slice 6 validation](docs/validation/SLICE_06_VALIDATION.md),
[Slice 5 validation](docs/validation/SLICE_05_VALIDATION.md),
[Slice 4 validation](docs/validation/SLICE_04_VALIDATION.md),
[Slice 3 validation](docs/validation/SLICE_03_VALIDATION.md),
[Slice 2 validation](docs/validation/SLICE_02_VALIDATION.md),
[Slice 1 validation](docs/validation/SLICE_01_VALIDATION.md), and the earlier
[Slice 0 record](docs/validation/SLICE_00_VALIDATION.md).
Validation records identify the commit under test when available. Slice 10 remains
uncommitted for review; Slice 9 and the real-repository validation fixes are committed
in the current baseline. Root ignore rules exclude generated output, local credentials and `.env`.
