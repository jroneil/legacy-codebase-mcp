# Slice 6 — MCP Interface validation

Date: 2026-10-01. Base commit: `783c14e`; validated the uncommitted working tree,
which also contains the prior Slice 3–5 changes. No commit made.

## Library and transport decision

- Dependency added: `org.springframework.ai:spring-ai-starter-mcp-server-webmvc:2.0.1`
  (one artifact). It resolves to the official MCP Java SDK 2.0.0
  (`io.modelcontextprotocol.sdk:mcp`, `mcp-core`, `mcp-json-jackson3`).
- Compatibility: the starter's own POM pins `spring-boot-starter-web` and
  `spring-boot-autoconfigure` to **4.1.1**, and the MCP SDK uses Jackson 3
  (`tools.jackson`), which is the Spring Boot 4.1.1 default. `dependency:tree`
  shows no version conflict and a single version of every MCP artifact.
- Transport: **Streamable HTTP** (MCP `2025-06-18`) on the existing Spring MVC
  servlet container at `/mcp`. Chosen over legacy SSE and over stdio because the
  service already runs as a local server with a database requirement.
- Client example (Codex CLI, `~/.codex/config.toml`):

  ```toml
  [mcp_servers.legacy-codebase]
  url = "http://127.0.0.1:8080/mcp"
  enabled = true
  ```

## Compatibility spike result

Spike (one dependency, one trivial `@McpTool`, packaged JAR against a throwaway
PostgreSQL 17.11 container): the application built and started on Spring Boot
4.1.1, registered the tool, and completed `initialize` → `notifications/initialized`
→ `tools/list` → `tools/call` over Streamable HTTP. One real finding: the
streamable auto-configuration is gated on `@ConditionalOnProperty(spring.ai.mcp.server.protocol=STREAMABLE)`,
and the documented default is not applied by that condition, so SSE was selected
when the property was absent. `spring.ai.mcp.server.protocol=STREAMABLE` is now
set explicitly. Spike code was removed; no application restructuring was needed.

## Commands and results

Backend commands ran in `backend/legacy`.

| Command | Result |
| --- | --- |
| `./mvnw -DskipTests compile` | Passed after the spike dependency was added |
| `./mvnw -Dtest=McpToolAdapterPurityTests,McpToolContractTests,McpRestEquivalenceTests,McpTransportSmokeTests test` | 24 passed (3 purity, 12 contract, 5 REST/MCP equivalence, 4 transport); 0 failures/errors/skips |
| `./mvnw test` | Run once at completion: **145 passed**, 0 failures/errors/skips, BUILD SUCCESS (121 before Slice 6) |
| `cd frontend/legacy-ui && PATH=/home/ai-dev/.nvm/versions/node/v24.18.0/bin:$PATH npm run build` | Run once at completion: production build, TypeScript and static generation passed; frontend unchanged |

Logs: `/tmp/legacy-slice6-spike2.log`, `/tmp/legacy-slice6-tools.log`,
`/tmp/legacy-slice6-final-backend.log`, `/tmp/legacy-slice6-final-frontend.log`.
No schema change (Flyway remains V4), no dependency upgrade, no Compose or
packaged-JAR validation repeated beyond the spike; integration tests use the
existing PostgreSQL 17.11 Testcontainers support.

## Acceptance evidence

**Passed — thin adapters.** `McpTools` holds exactly `SymbolStore`,
`TraversalQueries` and `FrameworkQueries`; it has no `JdbcTemplate`, no
transaction annotation and no analysis code. A reflection test enforces the
dependency set, and Mockito tests verify delegation (including traversal bounds
passed through unchanged to `TraversalQueries.query`). `McpResponses` only
re-shapes service records and adds bound metadata.

**Passed — tool contracts.** All eight tools are registered and callable:
`search_symbols`, `get_symbol`, `find_usages`, `trace_component`,
`list_database_tables`, `find_table_usages`, `inspect_location`,
`list_entry_points`. `tools/list` publishes correct required parameters, the
`OUTGOING`/`INCOMING` enum, descriptions, and `readOnlyHint=true`,
`destructiveHint=false`, `idempotentHint=true`, `openWorldHint=false`.

**Passed — bounded output and truncation.** List tools validate `limit` 1..200
(default 50) and return `returnedCount`, `totalCount`, `truncated`, `nextCursor`;
`nextCursor` resumes the next bounded page and invalid/out-of-range cursors are
rejected. Traversal tools validate `maxDepth` 0..16, `limit` 1..500 and `fanOut`
1..200, echo the applied bounds, and surface engine `truncationReasons`
(`DEPTH_LIMIT`, `RESULT_LIMIT`, …). Every serialized response contains an explicit
`truncated` flag. Truncated responses return exactly one page and no more.

**Passed — preserved semantics.** REST/MCP equivalence tests compare MCP views
against REST JSON for search, symbol detail, usages, entry points, traversal and
table impact, and find identical stable IDs, relationship types, ordering,
resolution states, path node lists, access kinds, directness and freshness.
Ambiguous names return ordered candidates with `selection=AMBIGUOUS` and no
traversal; `UNRESOLVED` edges keep a null target with their candidate
description; weakest-edge path confidence is unchanged.

**Passed — freshness metadata.** Every response carries `scanId`,
`gitCommitSha`, `analyzerVersion` and `scanCompletedAt` from the active
completed snapshot. With no active scan, list/search tools return `null`
freshness with empty results, and single-symbol/traversal tools return a
deterministic `error` string.

**Passed — `inspect_location`.** Accepts a repository-relative path and a 1-based
line, rejects absolute/escaping paths and lines `< 1`, returns `found=false` for
a valid path/line that no indexed symbol covers, and otherwise returns the
innermost containing symbol, its stable ID and kind, bounded enclosing symbols,
bounded outgoing and incoming relationships with evidence file/line/column, and
scan freshness. No source file content is returned.

**Passed — error handling.** Invalid stable IDs, no active scan, invalid bounds,
invalid cursors and invalid paths are reported deterministically in the `error`
field without stack traces, protocol failures or invented evidence.

**Passed — transport.** The handshake smoke test drives the real HTTP endpoint:
`initialize` returns protocol `2025-06-18`, server name `legacy-codebase-mcp` and
a tools capability; unused prompt/resource/completion capabilities are not
advertised; `tools/list` returns exactly the eight tools; `tools/call` round-trips
bounded JSON evidence; unknown tools are rejected by the transport.

**Passed — scope and safety.** MCP tools return only indexed evidence
(symbols, relationships, paths, tables) — never file contents, raw SQL/XML or
configuration snippets — so the Slice 4/3 sanitized index remains the only
snippet surface. The server binds loopback (`server.address=127.0.0.1`) and the
MCP endpoint is served from the same process. No schema, parser, analyzer,
scan, traversal or persistence behaviour changed; no Grails, AI summary,
frontend MCP feature or new parser family was added.

## Known limitations

- Tool errors are returned as a successful MCP tool result containing an `error`
  field; the MCP `isError` flag is not used. Clients should check `error` first.
- `list_database_tables` and `inspect_location` needed two new bounded read
  methods on the existing `SymbolStore` (`tables`, `locate`). They reuse the
  store's existing freshness, paging and row mapping and add no analysis logic;
  the REST surface was not extended.
- Cursors are opaque decimal offsets, not keyset cursors. They are stable only
  while the active snapshot is unchanged, which the returned `scanId` identifies.
- `list_database_tables` has no REST equivalent, so REST/MCP equivalence is
  asserted for the six tools that do have one.
- The Spring AI MCP annotation scanner logs non-fatal
  `BeanPostProcessorChecker` warnings at startup for its registry beans.
- A converted tool result is a JSON string inside MCP `content[].text`; no
  MCP `structuredContent` output schema is generated (`generateOutputSchema`
  left at its default).
- Streamable HTTP sessions are per-client and held in memory by the SDK; there
  is no authentication on the loopback endpoint.
- Repository has no root commit for this work; the base commit `783c14e`
  contains the uncommitted Slice 3–5 changes.

No unresolved Slice 6 acceptance blocker remains.
