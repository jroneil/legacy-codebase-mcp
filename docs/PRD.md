# Legacy Codebase MCP Server — Product Requirements Document

**Version:** 0.2  
**Status:** Draft  
**Date:** 2026-09-30

## 1. Purpose

The Legacy Codebase MCP Server provides deterministic, queryable code intelligence for legacy Struts and Grails applications. It statically analyzes a source repository, builds a normalized model of code symbols, framework mappings, dependency paths, and database usage, and exposes that model through MCP tools, REST endpoints, and a lightweight Next.js explorer.

The primary consumer is an engineering agent such as Codex or Claude. The human UI exists to inspect and validate the same indexed evidence used by MCP clients.

The first implementation baseline targets **Struts 1.x + Spring XML wiring + Java + JDBC/Hibernate-style persistence**. Grails support remains in scope, but follows validation of the Struts 1 analysis model.

## 2. Problem Statement

Legacy Struts and Grails applications frequently distribute behavior across Java or Groovy source, framework configuration, Spring wiring, services, DAOs, ORM mappings, JSP/GSP views, and embedded or external SQL. Understanding a single business flow can require manually tracing multiple files and technologies.

General-purpose coding agents can search source text, but they may consume large amounts of context, miss framework-specific relationships, or present inferred relationships as facts.

This project will create a deterministic analysis layer that answers questions such as:

- Where is this class or method used?
- What route maps to this Struts Action or Grails Controller?
- What does this action or controller depend on?
- Which database tables can this component read or write, directly or transitively?
- What chain of evidence connects an entry point to a database table?
- What symbols are associated with a file and line number from a stack trace or diff?
- What components could be affected by changing this symbol?
- What evidence supports a higher-level explanation of a module?

## 3. Goals

The system shall:

1. Scan a local legacy source repository without modifying it.
2. Index Java, Groovy, XML, SQL, JSP/GSP, and relevant configuration artifacts where supported.
3. Build a normalized symbol and relationship model.
4. Map Struts 1 framework constructs and Spring XML wiring into that model.
5. Add Grails framework mapping after the Struts 1 baseline is validated.
6. Identify database table usage where it can be determined from source, mappings, or supported persistence conventions.
7. Expose deterministic analysis through MCP tools.
8. Expose the same underlying analysis through REST APIs.
9. Provide a Next.js UI for human exploration and validation.
10. Preserve evidence for reported relationships, including source file and location where practical.
11. Distinguish resolved, inferred, and unresolved relationships and paths.
12. Continue to produce useful partial results when a legacy repository does not compile or cannot fully resolve its classpath.
13. Publish only completed scan snapshots for querying.
14. Return scan freshness metadata so agents know which source revision was analyzed.
15. Bound MCP responses so code intelligence saves context rather than flooding it.

## 4. Non-Goals for Initial Release

The initial release will not attempt to:

- replace a full Java IDE, compiler, or commercial static-analysis platform;
- provide complete runtime tracing;
- support both Struts 1 and Struts 2 in the first framework slice;
- guarantee resolution of reflection, dynamic dispatch, or all Grails conventions;
- execute the analyzed application;
- modify or refactor the target source repository;
- provide autonomous code changes;
- support every persistence technology or vendor-specific SQL dialect initially;
- require Elasticsearch, Neo4j, Redis, Kafka, Kubernetes, or a vector database;
- use an LLM as the source of truth for dependency or database relationships;
- provide production-scale multi-tenant hosting.

AI-assisted summaries may be added later, but deterministic indexed evidence remains authoritative.

## 5. Target Users

### Primary User

A software engineer using an AI coding agent to understand, maintain, or modernize a legacy Struts/Grails application.

### Secondary User

A developer or architect using the web UI to inspect code relationships, framework mappings, scan freshness, and database impact manually.

## 6. Core Use Cases

### 6.1 Find Symbol Usage

Given a class or method, return known references and evidence locations.

Example:

`find_usages("CustomerService")`

If a simple name is ambiguous, the system shall return candidates rather than silently selecting one.

### 6.2 Trace a Legacy Request Flow

Given a Struts action path, trace the known dependency path through Action, Spring-wired services, DAOs, and database tables.

Example:

`trace_component("/customer/search")`

Possible result:

`/customer/search -> CustomerAction -> CustomerService -> CustomerDAOImpl -> CUSTOMER`

Every hop shall include evidence and resolution state.

### 6.3 Determine Database Impact

Given a component, identify known tables read or written.

Example:

`list_database_tables("CustomerAction")`

The response shall distinguish direct and transitive results and include at least one supporting path for each transitive result.

### 6.4 Find Table Usage

Given a table name, identify SQL statements, ORM mappings, DAOs, services, actions, or controllers associated with it.

Example:

`find_table_usages("CUSTOMER")`

### 6.5 Inspect a Source Location

Given a repository-relative file path and line number, return the containing symbol and nearby indexed relationships.

Example:

`inspect_location("src/main/java/com/acme/CustomerAction.java", 143)`

This supports agent workflows that begin from stack traces, diffs, compiler errors, or search results.

### 6.6 Assess Change Impact

Given a symbol, return bounded incoming and outgoing relationships relevant to a change.

Initial impact analysis is evidence-based dependency traversal, not an AI-generated risk score.

### 6.7 Inspect a Module

Provide a structured view of a package/module including entry points, major classes, outgoing dependencies, and database tables.

A later phase may use an LLM to summarize this structured evidence in natural language.

## 7. Functional Requirements

### FR-1 Repository Scan

The system shall accept a configured local source-repository path and scan supported files without modifying the target repository.

The scanner shall record at minimum:

- relative path;
- file type;
- content hash or equivalent change identifier;
- file encoding when detected or configured;
- scan timestamp;
- scan status;
- git commit SHA when available;
- analyzer version.

The scanner shall support ignore rules for build output, dependency/vendor directories, binaries, and other configured exclusions. Initial defaults should include common paths such as `target/`, `build/`, `.gradle/`, `node_modules/`, and `.git/`.

Symlinks shall not permit traversal outside the configured repository root unless explicitly enabled.

### FR-2 Java Symbol Indexing

The system shall identify, where supported:

- packages;
- classes and interfaces;
- methods and signatures;
- fields;
- imports;
- containment;
- inheritance;
- interface implementations;
- overrides where resolvable;
- method and class references.

JavaParser with symbol solving is the initial preferred parser unless implementation findings justify another library.

The analyzer shall not require a successful application build. Missing jars, generated sources, unresolved types, or incomplete source roots shall degrade affected relationships to `INFERRED` or `UNRESOLVED` rather than failing the entire scan.

### FR-3 Spring / Dependency-Injection Wiring

The system shall index legacy dependency wiring from supported sources, including initially:

- `applicationContext.xml` and imported Spring XML files;
- bean IDs and implementation classes;
- constructor/property injection;
- interface-to-implementation relationships when determinable.

Where multiple candidate implementations exist and configuration does not disambiguate them, the relationship shall remain ambiguous rather than selecting one silently.

Later support may include Grails `resources.groovy` and annotation-based wiring as required by fixtures.

### FR-4 Grails Indexing

After the Struts 1 baseline is validated, the system shall identify Grails-related constructs where practical, including:

- controllers;
- controller actions;
- services;
- domain classes;
- URL mappings;
- service/property injection patterns;
- GORM/domain relationships where statically visible.

Dynamic behavior that cannot be reliably resolved shall be marked unresolved or inferred.

### FR-5 Struts 1 Mapping

The initial framework baseline shall target **Struts 1.x**.

The system shall parse supported configuration and identify relationships among:

- `web.xml` entry points where relevant;
- `struts-config.xml` action mappings;
- Action classes;
- `DispatchAction`-style method dispatch where determinable;
- ActionForms/form-beans where applicable;
- forwards;
- JSP views and action references where supported;
- source locations for configuration evidence.

The relationship model shall distinguish rendering a view from forwarding to another action. Struts 2 support is out of scope for the initial release.

### FR-6 Database and Persistence Mapping

The system shall identify database usage from supported sources such as:

- SQL embedded in Java or Groovy;
- JDBC and `JdbcTemplate` usage;
- DAO/repository SQL;
- Hibernate mappings;
- HQL/named queries where practical;
- stored procedure references;
- Grails domain/GORM conventions after Grails support is introduced;
- configuration or mapping files.

The model shall distinguish at minimum:

- `READS_TABLE`;
- `WRITES_TABLE`;
- `MAPS_TO_TABLE`;
- `EXECUTES_QUERY`;
- `DECLARES_QUERY`.

JSqlParser is the initial preferred SQL parser for supported syntax.

If SQL cannot be fully parsed because it is dynamically constructed, vendor-specific, or otherwise unsupported, the analyzer shall preserve available evidence and mark the result `INFERRED` or `UNRESOLVED` instead of discarding the statement or treating it as fully resolved.

Initial read/write rules shall cover common statements including `SELECT`, `INSERT`, `UPDATE`, `DELETE`, `MERGE`, and `INSERT ... SELECT`. Edge cases such as `SELECT ... FOR UPDATE` shall be documented and tested before being represented as write access.

### FR-7 Relationship Model

The system shall normalize discovered relationships into a common model.

Initial relationship types shall include:

- `CONTAINS`;
- `CALLS`;
- `IMPORTS`;
- `EXTENDS`;
- `IMPLEMENTS`;
- `OVERRIDES`;
- `INJECTS`;
- `WIRES_TO`;
- `ROUTES_TO`;
- `FORWARDS_TO`;
- `RENDERS`;
- `READS_TABLE`;
- `WRITES_TABLE`;
- `MAPS_TO_TABLE`;
- `DECLARES_QUERY`;
- `EXECUTES_QUERY`.

The generic `USES` relationship shall not be used where a more specific relationship can be represented.

Each relationship shall include provenance where available.

### FR-8 Resolution State and Path Confidence

Analysis results shall not represent uncertain relationships as confirmed facts.

Relationships shall support:

- `RESOLVED` — statically identified with direct evidence;
- `INFERRED` — derived from a documented convention, heuristic, or indirect evidence;
- `UNRESOLVED` — a reference exists but the target cannot be reliably determined.

For multi-hop paths, the path resolution state shall be the weakest edge on the path:

- all edges `RESOLVED` -> path `RESOLVED`;
- any `INFERRED` edge and no `UNRESOLVED` edge -> path `INFERRED`;
- any `UNRESOLVED` edge -> path `UNRESOLVED`.

Queries that return transitive database impact or dependency traces shall expose the path resolution state and supporting chain.

### FR-9 Stable Symbol Identity

Each indexed symbol shall have a deterministic identity derived from source-level identity rather than database row IDs alone.

For Java methods, identity should include at minimum fully-qualified declaring type, method name, and signature. Equivalent stable identities shall be defined for classes, fields, framework components, routes, tables, and source files.

Stable identity is required for repeatable scans, deduplication, and MCP references.

### FR-10 Scan Lifecycle and Snapshot Consistency

A scan shall be created as a new snapshot and shall not become queryable as the active snapshot until it completes successfully.

Queries shall read the latest completed active scan, not a partially written scan.

A failed scan shall leave the previous completed scan available.

Deleted or renamed files shall disappear from the newly completed snapshot rather than surviving as stale records.

Initial operational REST endpoints may include:

- `POST /api/scans`
- `GET /api/scans`
- `GET /api/scans/{id}`

Analysis data endpoints remain read-only.

### FR-11 MCP Interface

The initial MCP server shall expose a small deterministic tool set.

Initial candidate tools:

1. `search_symbols`
2. `get_symbol`
3. `find_usages`
4. `trace_component`
5. `list_database_tables`
6. `find_table_usages`
7. `inspect_location`
8. `list_entry_points`

Later tools may include:

- `impact_analysis`;
- `inspect_module`;
- `summarize_module`;
- `explain_database_usage`.

MCP tools shall delegate to the same application/query services used by REST rather than duplicate analysis logic.

Equivalent deterministic REST and MCP queries shall return logically equivalent evidence.

The initial transport choice shall be validated against the selected Spring-compatible MCP library before Slice 1 is considered architecturally complete. Local-agent setup documentation shall include at least one working client configuration example.

### FR-12 MCP Response Budgeting

MCP responses shall be bounded.

Large result sets shall support one or more of:

- explicit limits;
- pagination/cursors;
- maximum traversal depth;
- truncation flags;
- summary counts separate from detailed evidence.

A response that is truncated shall say so explicitly and provide a way to request the next bounded result set where practical.

### FR-13 REST Interface

The backend shall expose REST endpoints sufficient to support the Next.js UI, scan control, and validation of MCP output.

REST and MCP results for equivalent deterministic queries shall be derived from the same indexed model.

### FR-14 Next.js Explorer

The UI shall provide at minimum:

- symbol search;
- symbol detail;
- usages;
- incoming/outgoing relationships;
- framework mapping details;
- database table usage;
- path confidence and edge evidence;
- source evidence location;
- scan status and freshness metadata;
- analysis errors.

The UI is an inspection and demonstration interface, not the primary analysis engine.

### FR-15 Scan Repeatability

Repeated scans of unchanged source at the same commit and analyzer version should produce logically equivalent indexed results.

The implementation shall avoid creating duplicate symbols or relationships across scans.

### FR-16 Error Handling

Parser or resolver failures shall not abort the entire repository scan unless continuing would make the snapshot invalid.

The system shall record file-level analysis errors and make them inspectable.

Ambiguous names shall return candidate matches. The system shall not silently choose one when multiple plausible targets exist.

### FR-17 Secret and Sensitive-Configuration Redaction

Indexed configuration may contain credentials, tokens, connection strings, or other secrets.

Before source-derived configuration content or snippets are served through REST or MCP, the system shall apply redaction rules for supported secret patterns.

The deterministic indexer may inspect local source/configuration files, but external agents can receive source-derived data through MCP. Documentation shall state this boundary explicitly.

## 8. Static Analysis Model

The normalized model should support entities similar to:

- `SourceFile`
- `Symbol`
- `ClassSymbol`
- `MethodSymbol`
- `FrameworkComponent`
- `StrutsAction`
- `GrailsController`
- `GrailsService`
- `DomainClass`
- `DatabaseTable`
- `QueryArtifact`
- `Relationship`
- `Scan`
- `AnalysisError`

The implementation may simplify or normalize these entities as design evolves.

A relationship should be able to answer:

- What is the source?
- What is the target?
- What type of relationship is this?
- How was it determined?
- In which source file was the evidence found?
- At what line/location, when available?
- Is it resolved, inferred, or unresolved?
- Which scan produced it?

A path should additionally answer:

- What edges comprise the path?
- What is the weakest resolution state?
- Is the result direct or transitive?
- Was traversal truncated by depth or response limits?

## 9. Architecture

Initial architecture:

```text
Legacy Repository
      |
      v
Spring Boot Scanner / Analyzer
  |       |        |        |
 Java   Spring    Struts    SQL/ORM
 AST     XML       XML      Parsing
  \       |        |        /
   \______|________|_______/
          |
          v
  Normalized Code Model
          |
      PostgreSQL
       /      \
      /        \
 REST API      MCP Server
    |              |
    v              v
 Next.js        Coding Agents
   UI          (Codex/Claude/etc.)
```

The application shall initially remain a modular monolith.

The indexing pipeline and query model shall not depend on a successful build of the target application.

## 10. Technology Constraints

Initial technology choices:

- Java 21
- Spring Boot 4.1.1
- Maven
- Spring Web
- Spring Data JPA
- PostgreSQL
- **Flyway** for schema migration
- Bean Validation
- Spring Boot Actuator
- Next.js
- TypeScript
- Tailwind CSS
- JavaParser + symbol solving for Java analysis, subject to validation
- Groovy AST tooling for Grails/Groovy analysis, subject to validation
- standard XML parsing for Struts/Spring/Hibernate configuration
- JSqlParser for SQL analysis, subject to validation
- Docker Compose for local infrastructure
- Java/Spring-compatible MCP implementation

The MCP library must be compatibility-checked against the selected Spring Boot version before implementation depends on it.

No additional infrastructure shall be introduced without a demonstrated requirement.

## 11. Data Storage

PostgreSQL shall be the initial persistence store.

A graph database is not required for the initial release. Dependency traversal may use relational queries and recursive PostgreSQL CTEs where appropriate.

Recursive traversal shall include **cycle detection** in addition to a maximum depth.

Candidate persistence areas:

- scan metadata;
- active scan pointer/status;
- source files;
- symbols;
- framework components;
- database tables;
- queries;
- relationships;
- parser/analysis errors.

Bulk persistence should avoid naive per-row JPA inserts for large relationship sets. The implementation may use batching or JDBC-based bulk operations where justified by measured scan performance.

## 12. Security and Execution Model

The initial system is intended for local engineering use.

Requirements:

- Target repositories shall be treated as read-only input.
- Database credentials and other secrets shall be externalized.
- PostgreSQL should not require public network exposure in the default Compose configuration.
- REST/MCP exposure should default to local development use.
- Source content shall not be sent to external AI providers as part of deterministic indexing.
- MCP responses may expose source-derived code intelligence to a connected external agent; users shall be informed of that boundary.
- Secret-bearing configuration content shall be redacted before being returned through MCP or REST snippets.

If hosted AI is introduced later, it must be optional and explicitly configured.

## 13. AI Usage Principle

AI is not required to establish deterministic code relationships.

Static analysis and parser-derived evidence shall remain the source of truth for facts such as:

- symbol locations;
- imports;
- inheritance;
- framework mappings;
- discovered calls;
- dependency-injection wiring;
- SQL/table references.

An LLM may later explain, summarize, or synthesize indexed evidence, but generated text must not silently replace or override deterministic analysis.

## 14. Performance and Response Expectations

The initial release is intended for single legacy repositories on a developer workstation.

Initial performance goals are qualitative:

- a normal codebase scan should complete without exhausting workstation memory;
- common indexed queries should return interactively;
- the active snapshot shall remain queryable while a replacement scan is running;
- unchanged files should eventually be eligible for incremental-scan optimization;
- large query results shall be bounded or paginated;
- MCP responses shall prefer concise structured evidence over large source dumps.

Concrete performance thresholds should be established after the first real repository fixture is available.

## 15. Proposed Delivery Slices

### Slice 1 — Scan Foundation

- Configure target repository path.
- Add Flyway and initial schema.
- Discover supported source/configuration files.
- Apply ignore/symlink rules.
- Persist scan/file metadata, hashes, git SHA, analyzer version, and errors.
- Implement snapshot lifecycle: pending -> completed/failed -> active.
- Provide scan-control/status REST endpoints.

**Acceptance:** A fixture repository can be scanned repeatedly without duplicate active results; failed scans do not replace the previous active snapshot; deleted files are absent from a newly completed snapshot.

### Slice 2 — Java Symbol Index

- Add JavaParser and symbol solving.
- Index packages, classes, methods, imports, containment, inheritance, implementations, and basic references.
- Define stable symbol IDs.
- Degrade unresolved classpath references without failing the scan.
- Add symbol search/detail REST queries.

**Acceptance:** Known Java fixture symbols and usages are returned accurately; ambiguous simple names return candidates; missing classpath artifacts produce inspectable unresolved relationships rather than scan failure.

### Slice 3 — Struts 1 + Spring Wiring

- Parse `web.xml`, `struts-config.xml`, and Spring XML.
- Map action paths, Action classes, DispatchAction methods where possible, form-beans, forwards, and bean wiring.
- Distinguish `RENDERS` from `FORWARDS_TO`.
- Connect configuration mappings to indexed Java symbols.

**Acceptance:** A fixture Struts request path can be traced through configured Action and Spring-wired service/DAO components with evidence and resolution state.

### Slice 4 — Database Usage

- Parse supported SQL.
- Add JDBC/JdbcTemplate detection.
- Add supported Hibernate mappings and query artifacts.
- Index database tables and read/write relationships.
- Preserve partial evidence for unsupported/dynamic SQL.

**Acceptance:** Fixture SQL and mappings return expected table relationships, distinguish reads from writes, and visibly mark partial/unsupported analysis.

### Slice 5 — Relationship Traversal and Confidence

- Implement incoming/outgoing dependency queries.
- Add bounded multi-hop tracing with cycle detection.
- Compute path-level resolution from edge states.
- Add direct/transitive table-impact queries with evidence chains.

**Acceptance:** Known action -> service -> DAO -> table paths are reproducibly traced; path state matches the weakest edge; cycles terminate safely.

### Slice 6 — MCP Tools

- Validate Spring Boot/MCP library compatibility and transport.
- Expose initial deterministic MCP tools through existing query/application services.
- Add response limits, truncation metadata, and pagination where needed.
- Provide a sample Codex/Claude client configuration.

**Acceptance:** MCP and REST return logically equivalent evidence for the same fixture queries, and oversized MCP queries remain bounded.

### Slice 7 — Next.js Explorer

- Symbol search and detail.
- Relationship/path views.
- Framework mapping display.
- Database usage display.
- Source evidence locations.
- Scan freshness/error status.

**Acceptance:** A developer can inspect the same evidence exposed through MCP without querying PostgreSQL directly.

### Slice 8 — Grails Mapping

- Add representative Grails controllers, actions, services, domain classes, URL mappings, and wiring conventions.
- Record uncertainty for dynamic behavior.

**Acceptance:** Known fixture routes and controller/service relationships are returned with appropriate resolution state without weakening Struts baseline behavior.

### Later Slice — AI-Assisted Explanation

Potential capabilities:

- module summaries;
- natural-language impact explanations;
- modernization-oriented summaries.

This slice is explicitly dependent on the deterministic model being trustworthy first.

## 16. Initial Acceptance Criteria

The initial usable Struts-focused release shall demonstrate all of the following against controlled fixtures and at least one representative legacy repository sample:

1. A repository can be scanned without source modification or a successful application build.
2. Java symbols can be searched and inspected using stable identities.
3. Known usages can be returned with source evidence.
4. Ambiguous symbol names return candidate matches rather than an arbitrary choice.
5. At least one Struts 1 request path can be mapped through Action and Spring wiring.
6. SQL/ORM-derived table reads and writes can be identified for supported statements/mappings.
7. A component can be traced through multiple indexed relationships to a database table.
8. Direct and transitive database results are distinguishable and transitive results include evidence chains.
9. Path-level resolution reflects the weakest edge in the path.
10. Unresolved classpath references or unparseable SQL do not abort the complete scan.
11. Queries read only completed scan snapshots.
12. Failed scans preserve the previous active snapshot.
13. Scan responses expose git SHA and analyzer version where available.
14. Recursive traversal terminates safely in cyclic dependency graphs.
15. Equivalent REST and MCP queries use the same underlying analysis services.
16. MCP responses are bounded and explicitly report truncation when it occurs.
17. Source-derived configuration snippets are redacted for supported secret patterns before external exposure.
18. The Next.js UI can inspect indexed evidence and scan freshness.
19. Automated tests cover deterministic fixture expectations and negative/ambiguous cases.
20. The complete local environment can be started using documented commands and PostgreSQL via Docker Compose.

## 17. Testing and Validation Strategy

Testing shall emphasize deterministic fixtures rather than subjective output.

Expected layers:

- parser unit tests;
- symbol-resolution tests with complete and incomplete classpaths;
- Spring XML wiring tests;
- Struts mapping and DispatchAction tests;
- SQL/table extraction tests;
- dynamic/unparseable SQL negative fixtures;
- read/write edge-case tests;
- repository persistence and snapshot tests;
- relationship traversal and cycle tests;
- path-confidence composition tests;
- REST integration tests;
- MCP contract/integration tests;
- secret-redaction tests;
- frontend component tests;
- end-to-end smoke tests against a controlled legacy fixture application.

Synthetic fixtures prove deterministic behavior, but they are not sufficient for accuracy validation. Before calling the analyzer trustworthy, a sample of results shall be hand-verified against at least one representative messy legacy repository.

Later validation should compare selected engineering tasks using the MCP server versus plain text/grep-style discovery. Useful measures may include missed relationships, evidence accuracy, response size, and agent context consumption. This benchmark is desirable but is not a blocker for the first deterministic slices.

## 18. Open Questions

The following remain intentionally open and should be resolved through implementation evidence:

1. What should the default and maximum traversal depths be?
2. Does JavaParser + symbol solving provide acceptable accuracy on the target legacy repositories, or is Eclipse JDT needed?
3. Which source-root/classpath discovery strategy works best for Maven, Gradle, Ant, and partially buildable repositories?
4. Which SQL dialect fallbacks provide enough value when JSqlParser cannot parse vendor-specific SQL?
5. Which HQL, Criteria, stored-procedure, and ORM patterns should be promoted from later support into the initial database slice based on the target repository?
6. What source-location granularity is practical across Java, Groovy, XML, JSP/GSP, and SQL?
7. How much JSP action/form dependency extraction belongs in the first Struts slice versus a follow-on slice?
8. Which Grails versions will form the supported baseline for Slice 8?
9. Should incremental indexing be introduced after full-scan correctness or earlier for repository size reasons?
10. Which MCP transport provides the best local Codex/Claude workflow with the selected Java library?
11. What repository size and relationship count should establish measurable scan/query performance targets?
12. Which redaction patterns are required beyond obvious passwords, tokens, and connection-string credentials?

## 19. Product Principle

The project should prefer a smaller amount of trustworthy, explainable analysis over a larger amount of speculative intelligence.

Every high-level answer should be traceable back to indexed source evidence wherever practical.

A result is not stronger than its weakest supporting relationship.
