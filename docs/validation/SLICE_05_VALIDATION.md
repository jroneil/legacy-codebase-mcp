# Slice 5 validation

Date: 2026-10-01. Base commit: `814bfa08e7b2ca6b78398b5e6b706769230f2b27`;
validated the uncommitted working tree, including prior Slice 3–4 changes.

## Commands and results

| Command | Result |
| --- | --- |
| `cd backend/legacy && ./mvnw -Dtest=TraversalEngineTests,TraversalIntegrationTests test` | Final targeted run: 20 passed (12 engine, 8 PostgreSQL/REST); 0 failures/errors/skips. Earlier development run: 19 passed. |
| `cd backend/legacy && ./mvnw test` | Run once at completion: 121 passed; 0 failures/errors/skips; BUILD SUCCESS. |
| `cd frontend/legacy-ui && PATH=/home/ai-dev/.nvm/versions/node/v24.18.0/bin:$PATH npm run build` | Run once at completion: production build, TypeScript and static generation passed. |
| `git diff --check` | Passed. |

Logs: `/tmp/legacy-slice5-targeted.log`, `/tmp/legacy-slice5-final-backend.log`,
`/tmp/legacy-slice5-final-frontend.log`.
No schema/dependency changes; Flyway remains at V4. Existing migration tests passed
within the full suite. No separate parser, migration, Compose, packaged-JAR or
standalone HTTP smoke runs; endpoint behavior was exercised by REST integration tests.

## Acceptance evidence

- **Passed:** incoming/outgoing breadth-first traversal, shortest-first ordering,
  alternate paths, deterministic results after reversed insertion into a new snapshot.
- **Passed:** all nine confidence combinations; resolved, inferred and mixed unresolved
  paths; unresolved candidate descriptions remain terminal without invented targets.
- **Passed:** direct/transitive access, independent READ/WRITE paths to the same table,
  MAPPING distinct from access, complete relationship/file/location evidence chains.
- **Passed:** actual copied Struts/Spring fixture extended with JDBC SQL reaches
  `/customer/search → CustomerAction → customerService bean → customerDao bean →
  CustomerDAOImpl → method → query → CUSTOMER`. Path is INFERRED as required by
  the indexed Action-to-bean bridge. Repeated queries return identical evidence.
- **Passed:** per-path cycles, depth/zero-depth limits, no-path/external references,
  1,000-edge fan-out fixture, PostgreSQL fan-out, result/frontier bounds, and the
  5,000-unit graph-work budget even when no results match. Cutoffs are explicit.
- **Passed:** ambiguous branches and names remain separate; candidate overflow is
  flagged; scoped interface bindings/imports do not fabricate database impact.
- **Passed:** all three REST endpoints, incoming direction, freshness, invalid bounds,
  missing/no-active behavior, and result/depth truncation contracts.
- **Passed:** staged RUNNING/FAILED rows and uncommitted publication stay invisible;
  completed replacement becomes visible; deleted evidence disappears on replacement.
  Prior immutable-snapshot and stable-ID tests also pass in the full suite.
- Scope: query-only Slice 5; no target execution, MCP, Grails, AI, frontend features,
  new parsers, or commits. No blockers.

## Known limitations

- Static associations do not prove runtime execution. Containment and query declarations
  are included; evidence distinguishes DECLARES_QUERY from EXECUTES_QUERY.
- Direct means one graph edge; method → query → table is transitive. Alternate paths
  remain separate instead of aggregating away weaker evidence or read/write differences.
- Context-specific interface bindings are excluded from database walks; bean injection
  chains retain configuration context. Generic traces expose all indexed relationships.
- Unresolved references stop; degraded SQL artifacts remain visible through frontiers
  and terminal symbol state. Empty table results do not prove absence of database effects.
- Depth ≤16, results/frontiers ≤500 each, fan-out ≤200, graph-work ≤5,000 query/row units;
  candidates ≤100. Shortest-first applies to the explored graph. Explicitly truncated
  responses are incomplete; narrow the starting component or raise applicable bounds.
  No continuation cursor or runtime/data-flow expansion is provided.
