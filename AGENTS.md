# AGENTS.md

## Project

Legacy Codebase MCP Server

This repository builds a deterministic code-intelligence service for legacy Struts/Grails applications.

The system statically analyzes a legacy source repository, stores a normalized model of symbols, framework mappings, dependency relationships, and database usage, and exposes that evidence through REST, MCP, and a lightweight Next.js UI.

Primary documentation:

- `docs/PRD.md`
- `docs/IMPLEMENTATION_PLAN.md`

These documents are authoritative for scope and acceptance criteria.

---

## Core Engineering Principles

1. **Implement one slice at a time.**
   - Do not begin later slices unless explicitly asked.
   - Do not opportunistically add future features.

2. **Deterministic analysis is the source of truth.**
   - Static analysis, configuration parsing, and indexed evidence establish relationships.
   - Do not use an LLM to invent or confirm code relationships.

3. **Preserve uncertainty.**
   - Relationships use:
     - `RESOLVED`
     - `INFERRED`
     - `UNRESOLVED`
   - Never silently promote uncertain evidence to resolved.

4. **Evidence matters.**
   - Preserve source file and location where practical.
   - High-level answers should be traceable to indexed evidence.

5. **The target repository is read-only.**
   - Never modify analyzed source repositories.
   - Do not execute or rewrite target legacy applications unless explicitly required by a future approved slice.

6. **Partial analysis is acceptable.**
   - One malformed source file, missing jar, unresolved type, or unparseable SQL statement must not fail an otherwise valid scan.
   - Record localized analysis errors instead.

7. **Completed snapshots only.**
   - Queries must never read partially written scans.
   - Failed scans must not replace the previous active scan.

8. **Tests are gates.**
   - Do not weaken requirements to make tests pass.
   - If acceptance cannot be met, stop and report the blocker.

9. **Keep architecture simple.**
   - Initial application is a modular monolith.
   - PostgreSQL is the persistence store.
   - Do not add Redis, Kafka, Elasticsearch, Neo4j, Kubernetes, vector databases, or other infrastructure without a demonstrated requirement.

10. **Do not commit unless explicitly asked.**
    - Leave changes ready for review.

---

## Current Technology Baseline

Backend:

- Java 21
- Spring Boot 4.1.1
- Maven
- Spring Web
- Spring Data JPA
- PostgreSQL
- Flyway
- Bean Validation
- Actuator

Frontend:

- Next.js
- TypeScript
- Tailwind CSS

Planned analysis tooling:

- JavaParser + Symbol Solver
- standard XML parsing
- JSqlParser
- Groovy AST tooling later
- Java/Spring-compatible MCP implementation after compatibility validation

Do not add planned dependencies before the slice that needs them.

---

## Repository Layout

Expected layout:

```text
legacy-codebase-mcp/
├── backend/
│   └── legacy/
├── frontend/
│   └── legacy-ui/
├── docs/
│   ├── PRD.md
│   ├── IMPLEMENTATION_PLAN.md
│   └── validation/
├── docker-compose.yml
├── .gitignore
└── README.md
```

Do not restructure generated Spring Boot or Next.js projects without a clear requirement.

---

## Required Workflow Before Editing

Before modifying code:

1. Read `docs/PRD.md`.
2. Read `docs/IMPLEMENTATION_PLAN.md`.
3. Identify the explicitly requested slice.
4. Inspect the existing code relevant to that slice.
5. Summarize:
   - what already exists;
   - what will change;
   - which files are expected to change;
   - which tests are required;
   - any ambiguity or blocker.
6. Only then begin implementation.

If the request conflicts with the PRD or implementation plan, call it out before changing behavior.

---

## Slice Discipline

When asked to implement a slice:

- Implement that slice only.
- Do not begin work from the next slice.
- Do not add speculative abstractions for future slices.
- Do not add AI-assisted functionality before the deterministic model is validated.
- Do not add MCP before the MCP slice.
- Do not add Grails support before the Grails slice.
- Do not add frontend features before the UI slice unless required to keep the existing frontend build healthy.

If a later requirement affects an interface now, prefer the smallest stable seam rather than implementing the later feature.

---

## Persistence Rules

- Flyway owns schema migrations.
- Avoid relying on Hibernate auto-DDL as schema authority.
- Prefer validation against the migrated schema.
- Preserve immutable scan snapshots.
- Stable external identities must not depend only on database row IDs.
- Do not leave stale symbols or files from previous scans in a newly completed snapshot.
- Avoid naive per-row JPA inserts for very large relationship sets if measured performance shows a problem.
- Optimize only after measurement.

---

## Scan Rules

A scan should follow the lifecycle:

```text
PENDING
  -> RUNNING
  -> COMPLETED
  -> active
```

or:

```text
PENDING
  -> RUNNING
  -> FAILED
```

Rules:

- A scan becomes active only after successful completion.
- Failed scans preserve the previous active scan.
- Queries use the active completed scan.
- Repository-relative paths should be stored where practical.
- Do not permit symlink traversal outside the configured repository root by default.
- Default ignores should include common generated/vendor directories such as:
  - `.git/`
  - `target/`
  - `build/`
  - `.gradle/`
  - `node_modules/`

---

## Stable Identity Rules

Stable IDs must be deterministic across identical scans.

Examples:

```text
java:type:com.acme.customer.CustomerService
```

```text
java:method:com.acme.customer.CustomerService#findCustomer(java.lang.Long)
```

Do not expose auto-increment database IDs as the only external identity.

Ambiguous simple names must return candidates rather than silently selecting one.

---

## Relationship Rules

Prefer specific relationships over vague ones.

Examples:

```text
CONTAINS
CALLS
IMPORTS
EXTENDS
IMPLEMENTS
OVERRIDES
INJECTS
WIRES_TO
ROUTES_TO
FORWARDS_TO
RENDERS
READS_TABLE
WRITES_TABLE
MAPS_TO_TABLE
DECLARES_QUERY
EXECUTES_QUERY
```

Avoid a generic `USES` relationship when a more precise type applies.

Each relationship should preserve, where practical:

```text
scan
source symbol
target symbol or unresolved target description
relationship type
resolution state
source file
line/column or XML location
evidence type
```

---

## Resolution Rules

Use:

### RESOLVED

Directly established by parser/configuration evidence.

### INFERRED

Derived from a documented convention, heuristic, or incomplete static evidence.

### UNRESOLVED

A reference exists but the target cannot be determined reliably.

For paths, the weakest edge determines the path state:

```text
RESOLVED + RESOLVED = RESOLVED
RESOLVED + INFERRED = INFERRED
anything + UNRESOLVED = UNRESOLVED
```

Never represent an inferred or unresolved edge as resolved just to make a trace complete.

---

## SQL and Database Analysis Rules

When database analysis is in scope:

- preserve the original evidence location;
- distinguish reads from writes;
- support partial analysis;
- do not discard unparseable SQL silently;
- fallback heuristics must be marked `INFERRED`;
- unsupported dynamic SQL should remain `INFERRED` or `UNRESOLVED`.

Examples:

```text
SELECT -> READS_TABLE
INSERT -> WRITES_TABLE
UPDATE -> WRITES_TABLE
DELETE -> WRITES_TABLE
MERGE -> WRITES_TABLE
INSERT ... SELECT -> source READS_TABLE + destination WRITES_TABLE
```

Do not assume `SELECT ... FOR UPDATE` semantics without an explicit documented rule.

---

## MCP Rules

MCP should be a thin interface over existing application/query services.

Do not duplicate analysis logic inside MCP handlers.

Before depending on an MCP library:

1. verify Spring Boot compatibility;
2. verify transport support;
3. build the smallest compatibility spike;
4. document the decision.

MCP output must be bounded.

Use limits, cursors, maximum traversal depth, truncation flags, counts, or equivalent mechanisms.

Do not dump entire repositories or large source files into MCP responses.

Where practical, expose freshness metadata:

```text
scan_id
git_commit_sha
analyzer_version
scan_completed_at
```

---

## REST Rules

REST and MCP must use the same underlying services and indexed model.

Equivalent deterministic queries should return logically equivalent evidence.

Analysis endpoints are read-only.

Operational scan endpoints may create or inspect scan jobs.

---

## Frontend Rules

The Next.js UI is an inspection interface, not an analysis engine.

Do not put dependency inference or database-analysis logic in the frontend.

The UI should call backend APIs and display:

- symbols;
- usages;
- relationships;
- routes;
- table impact;
- evidence;
- resolution state;
- scan freshness;
- analysis errors.

Do not introduce heavy visualization dependencies unless simple presentation is proven insufficient.

---

## Security Rules

- Target repositories are read-only input.
- Database credentials must be externalized.
- Default services should remain local/private.
- Deterministic indexing must not send source code to external AI providers.
- MCP may expose source-derived information to connected agents; preserve the documented security boundary.
- Redact supported credentials, tokens, passwords, and secret-bearing connection strings before returning configuration snippets through REST or MCP.
- Never log raw secrets.

---

## Testing Expectations

Use deterministic fixtures.

Test the behavior, not implementation details.

Relevant test categories include:

- parser unit tests;
- scan lifecycle tests;
- snapshot consistency tests;
- stable ID tests;
- incomplete classpath tests;
- malformed source tests;
- Spring XML wiring tests;
- Struts mapping tests;
- SQL/table extraction tests;
- ambiguous-name tests;
- path-confidence tests;
- cycle-detection tests;
- REST integration tests;
- MCP contract tests;
- redaction tests;
- frontend tests;
- production builds.

Negative fixtures are required where uncertainty is part of the design.

---

## Validation Requirements

For each completed slice, create or update:

```text
docs/validation/SLICE_XX_VALIDATION.md
```

Record:

- date;
- commit under test if available;
- commands executed;
- test counts;
- build result;
- migration result;
- manual smoke result;
- acceptance criteria;
- known limitations;
- blockers or deferred items.

Do not claim acceptance without evidence.

---

## Build and Test Commands

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

Run additional slice-specific tests as needed.

If Docker Compose is part of the slice, validate startup/health as well.

---

## Change Scope

Prefer small, reviewable changes.

Avoid:

- unrelated refactoring;
- broad renaming;
- formatting entire files unnecessarily;
- changing generated project structure without cause;
- dependency upgrades unrelated to the slice;
- speculative abstraction layers;
- replacing a working design merely for stylistic preference.

Preserve existing behavior outside the requested scope.

---

## Failure Behavior

If an acceptance criterion cannot be satisfied:

1. stop;
2. preserve the repository in a reviewable state;
3. explain the blocker;
4. identify evidence;
5. propose the smallest next decision.

Do not weaken acceptance criteria or silently substitute different behavior.

---

## Review Checklist

Before reporting a slice complete, verify:

- requested slice only was implemented;
- PRD requirements were respected;
- implementation-plan requirements were respected;
- tests pass;
- builds pass;
- migrations apply cleanly;
- no secrets are exposed;
- uncertainty states are correct;
- no active-scan consistency regression exists;
- docs are updated;
- validation evidence exists;
- no later slice was started.

---

## Working Style

Prefer concise engineering reports.

At completion, report:

1. what changed;
2. important design decisions;
3. files changed;
4. tests/builds run;
5. acceptance status;
6. known limitations;
7. whether anything remains blocked.

Do not provide a large generic narrative when a concrete validation summary is sufficient.

---

## Current Product Principle

Prefer a smaller amount of trustworthy, explainable analysis over a larger amount of speculative intelligence.

A result is not stronger than its weakest supporting relationship.
