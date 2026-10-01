# Legacy Codebase MCP Server

Deterministic code intelligence for legacy Struts/Grails applications. Slice 5
adds bounded relationship traversal and database-impact paths over the immutable
Java, framework, and database index. MCP and the explorer follow in later slices.
The frontend remains the generated Next.js scaffold.

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
export ANALYZER_VERSION=database-usage-index-4

docker compose up -d --wait postgres
cd backend/legacy
./mvnw test
./mvnw package
./mvnw spring-boot:run
```

Flyway applies V1 (scan foundation), V2 (Java symbols), V3 (framework mappings),
and `V4__database_usage.sql` (database/query symbol and relationship kinds). Hibernate is configured
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
scan. `ANALYZER_VERSION` defaults to `database-usage-index-4` and can be overridden.

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

See [Slice 5 validation](docs/validation/SLICE_05_VALIDATION.md),
[Slice 4 validation](docs/validation/SLICE_04_VALIDATION.md),
[Slice 3 validation](docs/validation/SLICE_03_VALIDATION.md),
[Slice 2 validation](docs/validation/SLICE_02_VALIDATION.md),
[Slice 1 validation](docs/validation/SLICE_01_VALIDATION.md), and the earlier
[Slice 0 record](docs/validation/SLICE_00_VALIDATION.md).
Validation records identify the commit under test when available. Slice 3–5 changes
are uncommitted. Root ignore rules exclude generated output and local credentials.
