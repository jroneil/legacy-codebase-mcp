# Legacy Codebase MCP Server — Implementation Plan

**Version:** 0.1  
**Status:** Draft  
**Date:** 2026-09-30  
**Source:** PRD v0.2

## 1. Purpose

This plan translates the PRD into small implementation slices with explicit scope, acceptance gates, test requirements, and stop conditions.

The project should be built incrementally. Each slice must leave the repository in a working state and should be committed independently after validation.

The initial implementation focus is:

> Struts 1.x + Spring XML wiring + Java symbol analysis + JDBC/Hibernate-style database tracing, exposed through deterministic REST and MCP interfaces.

Grails support and AI-assisted summaries follow only after the Struts-focused analysis model is proven trustworthy.

---

## 2. Delivery Principles

1. **Docs first.** The PRD and this implementation plan define the intended behavior before implementation begins.
2. **One slice at a time.** Do not begin a later slice until the current slice passes its acceptance gates.
3. **Deterministic analysis first.** Static analysis and configuration parsing are authoritative; LLM output is not used to establish code relationships.
4. **Evidence over assertion.** Relationships should retain file/location provenance where practical.
5. **Uncertainty is explicit.** Relationships and paths must distinguish `RESOLVED`, `INFERRED`, and `UNRESOLVED`.
6. **Partial success is acceptable.** An unresolved classpath, unparseable SQL statement, or malformed source file should not invalidate an otherwise useful scan.
7. **Snapshots are immutable after publication.** Queries read only completed active scans.
8. **Tests are gates.** Failed tests, builds, migrations, or acceptance checks stop the slice.
9. **No speculative infrastructure.** Do not add Redis, Kafka, Elasticsearch, Neo4j, Kubernetes, or vector storage unless a measured requirement appears.
10. **Rollback remains simple.** Each slice should be independently revertible.

---

## 3. Repository Structure

Expected repository structure:

```text
legacy-codebase-mcp/
├── backend/
│   └── legacy/
├── frontend/
│   └── legacy-ui/
├── docs/
│   ├── PRD.md
│   └── IMPLEMENTATION_PLAN.md
├── docker-compose.yml
├── .gitignore
└── README.md
```

The generated Spring Boot and Next.js projects should remain structurally conventional unless a slice requires a deliberate change.

---

# Slice 0 — Baseline Validation

## Goal

Establish a clean, reproducible starting point before feature work begins.

## Scope

- Confirm Spring Boot project builds and tests.
- Confirm Next.js project builds.
- Add root `.gitignore` if not already present.
- Add root `docker-compose.yml` placeholder or initial PostgreSQL service only if required for Slice 1.
- Confirm Java 21, Node, npm, Maven wrapper, and project paths.
- Commit the untouched generated application baseline.

## Required Validation

Backend:

```bash
cd backend/legacy
./mvnw test
```

Frontend:

```bash
cd frontend/legacy-ui
npm run build
```

## Acceptance Gate

- Backend tests pass.
- Frontend production build passes.
- No generated build output is committed.
- Repository is committed as a clean baseline.

## Stop Conditions

Stop if either generated application does not build. Fix the baseline before adding project-specific code.

---

# Slice 1 — Scan Foundation

## Goal

Create the persistence and lifecycle foundation for repository scanning without implementing semantic code analysis yet.

## Backend Dependencies

Add only what is required:

- Flyway
- PostgreSQL runtime support already present if generated with the starter
- Testcontainers PostgreSQL, if used for integration tests

Do not add JavaParser, MCP, JSqlParser, or Groovy analysis yet.

## Core Model

Introduce the minimum persistence model required for immutable scans.

Suggested concepts:

```text
Scan
SourceFile
AnalysisError
ActiveScan / active pointer
```

### Scan

Suggested fields:

```text
id
status
repository_root
started_at
completed_at
git_commit_sha
analyzer_version
failure_message
```

Possible status values:

```text
PENDING
RUNNING
COMPLETED
FAILED
```

### SourceFile

Suggested fields:

```text
id
scan_id
relative_path
file_type
content_hash
encoding
size_bytes
```

### AnalysisError

Suggested fields:

```text
id
scan_id
source_file_id
stage
message
resolution_state
```

## Stable Rules

- Source repository is read-only.
- All stored paths are repository-relative unless explicitly marked otherwise.
- A scan becomes active only after successful completion.
- Failed scans never replace the previously active scan.
- Each scan owns its complete set of file records.
- Deleted files naturally disappear when a new snapshot becomes active.

## Repository Scanner

Implement basic file discovery.

Initial supported inventory categories:

```text
.java
.groovy
.xml
.sql
.jsp
.gsp
.properties
```

Ignore by default:

```text
.git/
target/
build/
.gradle/
node_modules/
```

Requirements:

- Do not follow symlinks outside the repository root.
- Calculate file hash.
- Detect or safely fall back for encoding.
- Continue scanning when individual files fail.
- Record errors for unreadable files.

No semantic parsing belongs in this slice.

## Scan Trigger

Implement operational endpoints:

```text
POST /api/scans
GET  /api/scans
GET  /api/scans/{id}
```

`POST /api/scans` may initially scan the configured repository root rather than accepting arbitrary roots from a request.

## Configuration

Externalize at minimum:

```text
LEGACY_REPOSITORY_ROOT
DATABASE_URL / standard Spring datasource properties
ANALYZER_VERSION
```

Repository root must not be hard-coded.

## Docker Compose

Add PostgreSQL.

Default behavior:

- PostgreSQL not publicly exposed unless required for local developer access.
- Credentials externalized or development-only defaults clearly documented.
- Backend may remain host-run during early slices.

## Flyway

Create migrations for Slice 1 tables.

Do not rely on Hibernate auto-DDL as the schema authority.

Recommended:

```properties
spring.jpa.hibernate.ddl-auto=validate
```

when practical.

## Tests

Required automated coverage:

- valid repository scan;
- ignore rules;
- nested files;
- symlink escape prevention;
- hash persistence;
- unsupported/binary files ignored;
- unreadable or malformed file does not fail entire scan;
- failed scan preserves previous active scan;
- completed scan becomes active;
- repeated unchanged scans do not duplicate records within a snapshot;
- deleted files disappear from a later active snapshot;
- git SHA collection when repository is Git-backed.

## Acceptance Gate

A controlled fixture repository can be scanned repeatedly, producing immutable completed snapshots with correct file inventory and scan metadata.

## Deliverables

- Flyway migrations
- scan persistence model
- repository file scanner
- scan lifecycle service
- scan REST endpoints
- fixture repository
- tests
- Slice 1 validation notes

## Stop Conditions

Do not begin Slice 2 if:

- failed scans can replace the active snapshot;
- partial scan data is queryable as active;
- migrations are unstable;
- symlink escape is possible;
- repeated scans produce inconsistent file inventories.

---

# Slice 2 — Java Symbol Index

## Goal

Index deterministic Java structure and basic references.

## Dependencies

Add:

- JavaParser
- JavaParser Symbol Solver

Verify versions explicitly against Java 21.

## Symbol Model

Introduce normalized symbol storage.

Suggested concepts:

```text
Symbol
Relationship
SourceLocation
```

Possible symbol categories:

```text
PACKAGE
CLASS
INTERFACE
METHOD
FIELD
```

## Stable Symbol Identity

Define deterministic keys before persistence logic is written.

Examples:

Class:

```text
java:type:com.acme.customer.CustomerService
```

Method:

```text
java:method:com.acme.customer.CustomerService#findCustomer(java.lang.Long)
```

Field:

```text
java:field:com.acme.customer.CustomerService#customerDao
```

Database row IDs may exist internally but must not be the external identity.

## Relationships

Implement only Java relationships needed by this slice:

```text
CONTAINS
IMPORTS
EXTENDS
IMPLEMENTS
OVERRIDES
CALLS
```

Each relationship should include:

```text
scan_id
source_symbol
target_symbol or unresolved target description
relationship_type
resolution_state
source_file
line/column where available
evidence_type
```

## Resolution Rules

Initial interpretation:

### RESOLVED

The source parser/symbol solver directly identifies the target.

### INFERRED

A target is strongly suggested by syntax or available source but cannot be fully symbol-resolved.

### UNRESOLVED

A reference exists but no reliable target can be determined.

Never invent a concrete target for an unresolved reference.

## Classpath Strategy

Start conservatively:

1. repository source roots;
2. standard Maven source structure where detected;
3. available dependency information if easily obtainable without requiring a successful target build.

The target repository must not have to compile.

Missing dependencies should degrade individual relationships, not fail scanning.

## Query API

Add REST endpoints sufficient for testing:

```text
GET /api/symbols/search?q=
GET /api/symbols/{stableId}
GET /api/symbols/{stableId}/usages
```

Exact endpoint shape may evolve, but query semantics should remain clear.

## Ambiguous Search Behavior

Simple-name search:

```text
CustomerService
```

must return all plausible candidates when multiple symbols match.

Do not silently choose one.

## Tests

Fixtures must cover:

- packages;
- classes/interfaces;
- overloaded methods;
- imports;
- inheritance;
- interface implementation;
- overrides;
- calls;
- ambiguous simple names;
- same method name in multiple types;
- incomplete classpath;
- missing dependency jar;
- malformed Java source;
- repeated scan stability;
- deterministic stable IDs.

## Acceptance Gate

Known Java fixture symbols and relationships are returned correctly, with evidence and uncertainty state.

## Stop Conditions

Do not proceed if:

- stable IDs change between identical scans;
- missing classpath entries abort the scan;
- ambiguous names are silently resolved;
- unresolved references are represented as confirmed relationships.

---

# Slice 3 — Struts 1 and Spring XML Wiring

## Goal

Trace Struts 1 entry points into Java code and Spring-wired dependencies.

## Supported Initial Inputs

```text
web.xml
struts-config.xml
applicationContext.xml
imported Spring XML
```

## Struts 1 Features

Index:

- Action mappings;
- Action classes;
- ActionForm/form-bean declarations;
- forwards;
- DispatchAction-style method dispatch where determinable;
- route/action path;
- JSP reference where directly visible in configuration.

Relationship types:

```text
ROUTES_TO
FORWARDS_TO
RENDERS
```

Keep `FORWARDS_TO` separate from `RENDERS`.

## Spring XML Features

Index:

- bean IDs;
- implementation classes;
- constructor injection;
- property injection;
- XML imports;
- interface-to-implementation wiring when resolvable.

Relationship types:

```text
INJECTS
WIRES_TO
```

When multiple implementations are possible, record ambiguity.

## Entry Point Query

Add a deterministic query such as:

```text
GET /api/entry-points
```

and a trace query capable of at least:

```text
/customer/search
    -> CustomerAction
    -> CustomerService
    -> CustomerDAOImpl
```

Database tables are not required until Slice 4.

## Fixtures

Create a small Struts 1 fixture with:

- one Action;
- one DispatchAction;
- one ActionForm;
- multiple forwards;
- one Spring service interface;
- one service implementation;
- one DAO interface;
- one DAO implementation;
- XML property or constructor wiring;
- at least one ambiguous implementation fixture.

## Tests

Required:

- action path to Action;
- DispatchAction method mapping;
- ActionForm linkage;
- forward-to-view;
- forward-to-action;
- Spring bean resolution;
- interface-to-implementation mapping;
- imported Spring XML;
- ambiguous implementation handling;
- missing bean class;
- malformed XML;
- source evidence location.

## Acceptance Gate

At least one fixture request path can be traced from Struts configuration through Action and Spring-wired service/DAO layers with per-edge evidence and resolution state.

## Stop Conditions

Do not proceed if Struts or Spring XML relationships are represented without provenance, or ambiguous wiring is silently resolved.

---

# Slice 4 — Database Usage Analysis

## Goal

Map supported persistence behavior to database tables.

## Dependencies

Add:

- JSqlParser

Do not add additional SQL parser libraries unless JSqlParser limitations are demonstrated by fixtures.

## Initial Supported Sources

- JDBC SQL strings;
- JdbcTemplate calls;
- DAO SQL constants;
- Hibernate mapping XML;
- basic HQL/named queries where practical;
- stored procedure references where statically identifiable.

GORM belongs to the Grails slice.

## Query Model

Introduce:

```text
DatabaseTable
QueryArtifact
```

Relevant relationships:

```text
READS_TABLE
WRITES_TABLE
MAPS_TO_TABLE
DECLARES_QUERY
EXECUTES_QUERY
```

## Read/Write Rules

Document and test initial handling.

### Read

Typical:

```text
SELECT
```

### Write

Typical:

```text
INSERT
UPDATE
DELETE
MERGE
```

### Mixed

`INSERT ... SELECT`:

- source tables: `READS_TABLE`
- destination table: `WRITES_TABLE`

`SELECT ... FOR UPDATE`:

Do not classify automatically as a write unless a documented project rule is adopted. Preserve statement type and evidence.

## Dynamic SQL

For concatenated SQL:

```java
"select * from " + tableName
```

do not pretend the final table is resolved.

Preserve:

- source expression;
- partial literals;
- parser failure;
- `INFERRED` or `UNRESOLVED`.

## SQL Parser Fallback

If JSqlParser fails:

1. preserve original source location;
2. record parse error;
3. extract conservative evidence only if a documented heuristic is used;
4. mark heuristic output `INFERRED`;
5. never mark fallback heuristics `RESOLVED`.

## Tests

Cover:

- SELECT;
- INSERT;
- UPDATE;
- DELETE;
- MERGE;
- INSERT SELECT;
- joins;
- multiple tables;
- JdbcTemplate;
- prepared statements;
- Hibernate table mapping;
- named query;
- stored procedure reference;
- dynamic concatenated SQL;
- unsupported/vendor SQL;
- Oracle-style syntax fixture if available;
- malformed SQL.

## Acceptance Gate

Known fixture persistence paths produce correct tables and access classifications, with unsupported or dynamic SQL visibly degraded rather than silently discarded.

## Stop Conditions

Do not proceed if parse failures discard evidence silently or if inferred tables are reported as resolved.

---

# Slice 5 — Relationship Traversal and Path Confidence

## Goal

Provide bounded multi-hop dependency tracing.

## Traversal Requirements

Queries must support:

- incoming edges;
- outgoing edges;
- direct relationships;
- transitive relationships;
- maximum depth;
- cycle detection;
- deterministic ordering where practical.

## Path Confidence

Path state is the weakest edge:

```text
RESOLVED + RESOLVED = RESOLVED

RESOLVED + INFERRED = INFERRED

anything + UNRESOLVED = UNRESOLVED
```

## Database Impact Query

Implement:

```text
list_database_tables(component)
```

Responses should distinguish:

```text
direct
transitive
```

Each transitive table must include at least one evidence chain.

Conceptual response:

```json
{
  "table": "CUSTOMER",
  "access": "WRITE",
  "direct": false,
  "resolution": "INFERRED",
  "path": [
    "CustomerAction.update",
    "CustomerService.update",
    "CustomerDAOImpl.save",
    "CUSTOMER"
  ]
}
```

## Cycle Handling

Traversal must track visited path nodes/edges.

A depth limit alone is not sufficient.

Fixture:

```text
ServiceA -> ServiceB -> ServiceA
```

must terminate safely.

## Query Endpoints

Add REST coverage for:

```text
trace_component
list_database_tables
find_table_usages
```

Exact URLs can remain conventional REST paths.

## Tests

- resolved path;
- inferred path;
- unresolved path;
- mixed path;
- shortest path if multiple paths exist;
- multiple evidence paths;
- direct vs transitive table access;
- cycle;
- max depth;
- no path;
- large fan-out bounded result.

## Acceptance Gate

Known Action -> Service -> DAO -> Table chains are reproducibly returned, and path confidence matches the weakest edge.

## Stop Conditions

Do not begin MCP work if traversal can loop indefinitely, path state is incorrect, or transitive tables are returned without supporting evidence.

---

# Slice 6 — MCP Interface

## Goal

Expose the proven deterministic analysis to coding agents.

## Pre-Implementation Compatibility Spike

Before committing to an MCP dependency:

1. identify the Java/Spring MCP library;
2. verify Spring Boot 4.1.1 compatibility;
3. verify supported transport;
4. build the smallest possible hello-tool spike;
5. remove spike code if it is not production-worthy;
6. document the decision.

Do not restructure the application around MCP until compatibility is proven.

## Initial MCP Tools

Implement:

```text
search_symbols
get_symbol
find_usages
trace_component
list_database_tables
find_table_usages
inspect_location
list_entry_points
```

All tools must call existing application/query services.

MCP-specific duplicate analysis logic is prohibited.

## inspect_location

Input:

```text
repository-relative file path
line number
```

Return where available:

- containing symbol;
- stable symbol ID;
- symbol kind;
- relevant incoming/outgoing edges;
- source evidence;
- scan metadata.

## Response Budgeting

Every MCP tool must have bounded output.

Use as applicable:

```text
limit
cursor
max_depth
truncated
total_count
returned_count
```

A truncated response must say that it is truncated.

Do not return entire source files unless a future explicit tool requires it.

## Freshness Metadata

MCP responses should expose:

```text
scan_id
git_commit_sha
analyzer_version
scan_completed_at
```

where practical.

## Secret Redaction

Before MCP returns source snippets or configuration evidence:

- redact supported passwords;
- redact tokens;
- redact connection-string credentials;
- test false-positive and false-negative cases.

## Client Setup

Document at least one working local client setup for a coding agent.

Transport choice should be based on the compatibility spike rather than assumed in advance.

## Tests

- MCP tool contract tests;
- REST/MCP logical equivalence;
- response bounds;
- truncation;
- ambiguous symbol query;
- stale scan metadata;
- redaction;
- no active scan;
- invalid stable ID;
- inspect_location line outside file bounds.

## Acceptance Gate

An external MCP client can query the fixture repository and receive bounded, deterministic evidence equivalent to REST.

## Stop Conditions

Do not proceed if MCP introduces separate business logic, outputs are unbounded, or library compatibility is uncertain.

---

# Slice 7 — Next.js Explorer

## Goal

Provide a simple human inspection UI over the same backend evidence.

## UI Scope

Keep the interface deliberately small.

Pages/views:

```text
Search
Symbol Detail
Entry Points
Trace
Table Usage
Scan Status
Analysis Errors
```

## Search

Support:

- simple symbol query;
- type/kind display;
- candidate selection for ambiguous names.

## Symbol Detail

Show:

- stable ID;
- package/type;
- source location;
- incoming relationships;
- outgoing relationships;
- resolution state;
- evidence location.

## Trace View

Show:

```text
Action
  -> Service
  -> DAO
  -> Table
```

For each edge:

- type;
- resolution;
- source file;
- line/location.

## Scan Status

Show:

- active scan ID;
- status;
- git SHA;
- analyzer version;
- completed time;
- file count;
- error count.

## Analysis Errors

Allow basic inspection of:

- source file;
- analyzer stage;
- message;
- unresolved/parse state.

## Constraints

- No frontend-only business logic for relationship inference.
- No direct PostgreSQL access.
- No AI summary functionality.
- No complex graph visualization required initially.

## Tests

- component tests;
- API failure states;
- ambiguous search handling;
- truncated query display;
- no active scan state;
- basic production build.

## Acceptance Gate

A developer can inspect the same symbols, routes, relationships, table impact, confidence, and freshness metadata available through MCP.

## Stop Conditions

Do not introduce a heavy visualization library unless the basic UI proves insufficient.

---

# Slice 8 — Grails Mapping

## Goal

Extend the already-proven relationship model to representative Grails applications.

## Compatibility Decision

Before broad implementation, choose a specific Grails baseline based on an actual target repository or fixture.

Do not claim generic Grails support.

## Initial Targets

Potentially:

```text
UrlMappings
Controller
Controller action
Service
Domain class
resources.groovy
GORM mapping
dynamic finders
```

## Rules

- deterministic constructs should map into existing normalized entities;
- Grails conventions that are not fully statically resolvable must be `INFERRED`;
- unsupported metaprogramming should remain `UNRESOLVED`;
- do not weaken the Struts implementation to force generic abstractions.

## Tests

- URL mapping;
- controller action;
- service injection;
- domain/table mapping;
- resources.groovy wiring;
- dynamic finder inference;
- ambiguous dynamic behavior;
- unsupported runtime metaprogramming.

## Acceptance Gate

Representative Grails fixture flows map into the existing relationship model with uncertainty correctly represented.

---

# Slice 9 — Real Legacy Repository Validation

## Goal

Validate accuracy against a messy repository rather than synthetic fixtures alone.

This may occur earlier if a suitable repository is available, but it must happen before calling the analyzer trustworthy.

## Procedure

Select a limited set of manually verifiable questions.

Examples:

```text
Where is CustomerAction used?
What service does /customer/search invoke?
Which tables can CustomerAction touch?
Where is CUSTOMER written?
What calls CustomerDAOImpl.save?
```

For each:

1. manually trace the answer;
2. record expected relationships;
3. run analyzer query;
4. compare result;
5. classify false positives;
6. classify false negatives;
7. document unresolved cases.

## Metrics

Initial useful metrics:

```text
verified relationships
true positives
false positives
false negatives
unresolved references
inferred references
```

Do not claim precision/recall for the whole repository unless sampling is sufficient to justify it.

## Acceptance Gate

The analyzer demonstrates useful, explainable results on real legacy code and identified failure modes are documented.

---

# Later Slice — AI-Assisted Explanation

## Preconditions

Do not begin until:

- deterministic relationship model is stable;
- real-repository validation is complete enough to trust the evidence;
- MCP tools are useful without AI summaries.

## Possible Features

```text
summarize_module
explain_database_usage
explain_change_impact
modernization_summary
```

## Rule

AI output consumes indexed evidence.

AI output must not silently manufacture deterministic relationships.

When evidence is incomplete, generated explanations must preserve that uncertainty.

---

# Cross-Cutting Requirements

## A. Provenance

Every relationship should preserve, where practical:

```text
source file
line/column or XML location
analyzer/parser
resolution state
scan ID
```

## B. Error Isolation

Errors should be scoped as narrowly as possible.

Examples:

```text
one malformed Java file != failed repository scan
one SQL parse failure != failed DAO analysis
one unresolved jar != failed symbol index
```

## C. Determinism

For identical source + analyzer version, repeated scans should produce logically equivalent symbols and relationships.

## D. Security

- analyzed repository is read-only;
- repository root cannot be escaped accidentally;
- credentials are externalized;
- secret-bearing snippets are redacted before REST/MCP exposure;
- deterministic indexing does not send source externally.

## E. Performance

Do not optimize prematurely.

Measure first.

Likely optimization points later:

```text
batch inserts
JDBC bulk writes
indexes
incremental file parsing
query caching
```

Do not introduce distributed infrastructure for local workstation use.

---

# Definition of Done for Every Slice

A slice is complete only when all applicable items are true:

- intended scope implemented;
- out-of-scope work was not added;
- backend tests pass;
- frontend tests/build pass where applicable;
- Flyway migrations apply from a clean database;
- Docker Compose dependencies start successfully where applicable;
- acceptance behavior is manually smoke-tested;
- security behavior relevant to the slice is validated;
- docs are updated;
- validation evidence is recorded;
- no later slice was started;
- working tree is ready for a clean commit.

---

# Suggested Validation Document Pattern

Create one validation note per slice:

```text
docs/validation/
  SLICE_01_VALIDATION.md
  SLICE_02_VALIDATION.md
  ...
```

Each validation note should record:

```text
date
commit under test
commands executed
test counts
build result
manual smoke result
known limitations
acceptance criteria result
```

---

# Codex Working Contract

Codex should receive one slice at a time.

Recommended prompt pattern:

> Read `docs/PRD.md` and `docs/IMPLEMENTATION_PLAN.md`. Implement **Slice N only**. Do not begin later slices. First inspect the existing repository and summarize the planned changes. Preserve current behavior outside this slice. Add or update tests required by the slice. Run all relevant tests and builds. If an acceptance criterion cannot be met, stop and report the blocker rather than weakening the requirement. Do not commit changes.

For review-only passes:

> Review Slice N against `docs/PRD.md` and `docs/IMPLEMENTATION_PLAN.md`. Do not modify code. Identify requirement gaps, incorrect assumptions, missing tests, security issues, and anything that would cause acceptance to fail.

---

# Recommended Immediate Next Step

After adding this plan to the repository:

1. run the untouched backend tests;
2. run the untouched frontend production build;
3. commit the baseline;
4. give Codex the **Slice 1 only** prompt;
5. require Codex to inspect and propose changes before editing;
6. review the Slice 1 design before implementation if it introduces anything beyond PostgreSQL, Flyway, scan metadata, file inventory, and scan lifecycle.

The project should prove one layer at a time:

```text
files
  -> symbols
  -> framework wiring
  -> database evidence
  -> relationship paths
  -> MCP
  -> UI
  -> Grails
  -> AI explanation
```

That ordering keeps the difficult part of the project — trustworthy legacy-code analysis — ahead of presentation and AI features.
