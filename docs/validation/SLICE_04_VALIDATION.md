# Slice 4 — Database Usage Analysis validation

Date: 2026-10-01. **Acceptance passed.** Base commit:
`814bfa08e7b2ca6b78398b5e6b706769230f2b27`, plus existing uncommitted Slice 3 and
new Slice 4 changes. No commit made. Added dependency:
`com.github.jsqlparser:jsqlparser:5.4`, validated on Java 21.0.12.1.

## Commands and results

Backend commands ran in `backend/legacy`; equivalent root invocations used
`./backend/legacy/mvnw -f backend/legacy/pom.xml`.

| Command | Result |
| --- | --- |
| `./mvnw dependency:resolve` | JSqlParser 5.4 resolved |
| `./mvnw -DskipTests compile` | Passed |
| `./mvnw -Dtest=DatabaseIndexerTests test` | Development checks; classifier fixes verified |
| `./mvnw -Dtest=DatabaseIndexerTests,DatabaseIntegrationTests test` | Final targeted run: **38 passed** (32 indexer/parser, 6 PostgreSQL/REST), no failures/errors/skips |
| `./mvnw test` | Run once at end: **101 passed**, no failures/errors/skips; compilation passed |
| `cd frontend/legacy-ui && PATH=/home/ai-dev/.nvm/versions/node/v24.18.0/bin:$PATH npm run build` | Run once at end: production build and TypeScript passed; frontend unchanged |
| `git diff --check` | Passed |

Logs: `/tmp/legacy-slice4-dependencies.log`, `/tmp/legacy-slice4-targeted.log`,
`/tmp/legacy-slice4-final-backend.log`, `/tmp/legacy-slice4-final-frontend.log`.
Existing PostgreSQL 17.11 Testcontainers support exercised fresh migrations and
REST integration. No standalone HTTP, packaged-JAR or full Compose smoke repeated.
Development tests exposed CTE case/scope handling, first-statement-only parsing,
and UPDATE alias misclassification; fixed before final validation. No checks disabled.

## Acceptance evidence

- Fixtures cover SELECT, INSERT, UPDATE, DELETE, MERGE, INSERT…SELECT, joins,
  multiple tables, JDBC Statement/PreparedStatement/CallableStatement, JdbcTemplate,
  constants, Hibernate XML mappings, named/native queries, basic HQL, procedure
  references, dynamic concatenation, malformed SQL, Teradata LOCKING syntax and
  Oracle `(+)` joins. All database edges retain positive source locations.
- SELECT…FOR UPDATE produces READS_TABLE only. Writes target the DML destination;
  explicit sources/subqueries add reads. INSERT…SELECT from the destination itself
  records both read and write. Aliases/CTEs are not invented physical tables,
  including mixed-case, recursive and nested CTE scopes.
- DatabaseTable/QueryArtifact are scan-scoped symbol kinds. Query metadata retains
  statement type, parse status, sanitized expression/template/partial literals and
  static procedure name. SQL parse failures, unsupported/multiple statements and
  dynamic expressions remain UNRESOLVED artifacts with localized diagnostics and
  no guessed tables. No SQL fallback heuristic produces resolved output.
- Constant folding respects final fields, static imports, unchanged locals,
  reassignment and lexical shadowing. Unrelated methods named `query` are excluded.
  Preparing/declaring SQL is distinct from executing it. Procedure bodies remain opaque.
- Explicit Hibernate table mappings produce MAPS_TO_TABLE. Basic HQL uses explicit
  entity mappings with INFERRED edges; conflicting entities/named queries retain
  uncertainty. Missing/dynamic mappings and malformed XML produce diagnostics.
- Existing REST search/detail/usages expose direct table/query evidence and scan
  freshness. Quoted SQL values, Java string literals, comments, Oracle/dollar-quoted
  secrets and partial quoted secrets are absent from artifact previews. Secure
  rereads reject changed hashes/symlink substitutions; XML entities cannot supply
  evidence. Scan input hashes remain unchanged.
- Flyway V4 applies on fresh PostgreSQL and upgrades populated V3 while preserving
  Java/framework evidence and the active pointer. Existing immutable-row guards
  reject query/relationship edits; Hibernate does not create schema.
- Uncommitted publication exposes the previous active model; committed RUNNING and
  FAILED staging rows stay hidden. Invalid database-edge insertion rolls back the
  snapshot and preserves the previous active scan. Repeated identities/edges are
  stable; deleted evidence disappears only from the newly completed snapshot.
- All 63 earlier backend tests pass. No Slice 5 traversal, MCP, Grails, AI summaries
  or frontend database views added. Prior Slice 3 work preserved.

## Known limitations

- Direct evidence only; no transitive database impact. Table identities preserve
  source spelling/qualification/quoting; no live catalogue, default-schema or
  datasource equivalence is assumed.
- Source-level declared API types, constants and unchanged locals only. No general
  data flow, StringBuilder reconstruction, inherited/custom wrappers, callback or
  batch-array analysis, or standalone SQL scripts. Incomplete imports/classpaths
  can prevent API recognition; JdbcTemplate/Hibernate execution edges are inferred.
- Hibernate `*.hbm.xml` explicit mappings and basic single-entity HQL SELECT/FROM
  with optional simple predicate only; HQL primary-table access is inferred. No
  advanced HQL, annotations, inheritance/naming strategies, procedure bodies,
  triggers, or quoted/dynamic procedure-name resolution.
- SQL limit 65,536 characters, one-second parser timeout, nesting 100; constant
  evaluation depth 32. Existing secure reread/XML bounds remain. Metadata previews
  are bounded to 4,000 characters and marked when truncated.

No unresolved Slice 4 acceptance blocker remains.
