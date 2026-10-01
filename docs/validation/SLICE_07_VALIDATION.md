# Slice 7 — Next.js Explorer validation

Date: 2026-10-01. Base commit: `783c14e`; validated the uncommitted working tree,
which also contains the prior Slice 3–6 changes. No commit made.

## Files changed

Modified:

- `frontend/legacy-ui/app/layout.tsx` — nav shell, product metadata, shared main container
- `frontend/legacy-ui/app/page.tsx` — replaced the starter page with symbol search
- `frontend/legacy-ui/package.json`, `frontend/legacy-ui/package-lock.json` — added the
  `vitest` dev dependency and `test` / `test:watch` scripts

Added:

- `frontend/legacy-ui/vitest.config.ts`
- `frontend/legacy-ui/lib/api.ts` — typed, read-only REST client
- `frontend/legacy-ui/lib/view.ts` — chain ordering/evidence/label formatting only
- `frontend/legacy-ui/app/symbols/[id]/page.tsx`, `app/entry-points/page.tsx`,
  `app/entry-points/trace/page.tsx`, `app/trace/page.tsx`, `app/tables/page.tsx`,
  `app/tables/[id]/page.tsx`, `app/scan/page.tsx`, `app/errors/page.tsx`
- `frontend/legacy-ui/components/` — `Nav`, `Freshness`, `SymbolResults` (+ `CandidateList`),
  `RelationshipList`, `TraceView`, `TableImpactList`, `Pagination`, `ui`
- `frontend/legacy-ui/lib/api.test.ts`, `lib/view.test.ts`,
  `frontend/legacy-ui/components/components.test.tsx`, `frontend/legacy-ui/test/fixtures.ts`,
  `frontend/legacy-ui/test/pages.test.tsx`

No backend file changed.

## Commands and test counts

Commands ran in `frontend/legacy-ui` with Node 24.18.0.

| Command | Result |
| --- | --- |
| `npm test` | **47 passed**, 0 failed: `lib/api.test.ts` 7, `lib/view.test.ts` 8, `components/components.test.tsx` 14, `test/pages.test.tsx` 18 |
| `npx tsc --noEmit` | Passed (route types generated with `npx next typegen`) |
| `npm run lint` | Passed, no warnings |
| `npm run build` | Passed: compiled, TypeScript, 3/3 static pages; 9 app routes emitted (`/`, `/entry-points`, `/entry-points/trace`, `/errors`, `/scan`, `/symbols/[id]`, `/tables`, `/tables/[id]`, `/trace`) |
| Route smoke (`next start` on 127.0.0.1:13000 plus a local stub backend on 127.0.0.1:8080) | All 9 routes returned HTTP 200 and rendered real stub evidence |

Logs: `/tmp/legacy-slice7-final-test.log`, `/tmp/legacy-slice7-final-build.log`,
`/tmp/legacy-slice7-build.log`, `/tmp/slice7-lint.log`. Backend tests were not re-run
because no backend file changed; MCP, Compose, packaged-JAR, parser and database smoke
validation were not repeated.

## Acceptance evidence

- **Symbol search** (`/`): query form, results with stable ID, kind, resolution state and
  source location, plus `Showing n–m of total` and a Next link when the backend page is
  truncated. Empty matches and an empty query are handled.
- **Ambiguous candidates**: search lists every candidate as its own link (verified live:
  `one:Shared` and `two:Shared` both selectable). An `AMBIGUOUS` traversal selection
  renders `CandidateList`, which states that the backend did not select one and offers
  every candidate — no auto-selection anywhere.
- **Symbol detail** (`/symbols/[id]`): stable ID, kind, state, qualified name, signature,
  source range, scan freshness, outgoing relationships and incoming relationships/usages,
  each with type, resolution state, target (or unresolved description) and evidence
  `file:line:column`. Bounded pages state when they are truncated.
- **Entry points** (`/entry-points`, `/entry-points/trace`): exact-path filter, route,
  declaration file/line, dispatch parameter, and the configuration path as an ordered
  chain with per-edge type, state and evidence.
- **Trace** (`/trace`): component/direction/depth/limit/fanOut form, applied backend
  bounds, selection (or candidates when ambiguous), each path rendered as
  `node → edge → node → …` in backend order with `resolutionState` and `termination`,
  plus frontiers, `truncated` and ordered `truncationReasons`.
- **Table impact** (`/tables`, `/tables/[id]`): read/write/mapping access badges, direct
  vs transitive, path resolution state, and the full supporting evidence path per impact;
  the table page lists the components that use the table with links back to `/trace`.
- **Scan status/freshness** (`/scan`): active scan ID, status, git SHA, analyzer version,
  completed time, file count, error count, repository root, and the recent-scan list.
  Every analysis page also shows the freshness block or the no-active-scan notice.
- **Analysis errors** (`/errors`): source file, analyzer stage, code, resolution state and
  message for the active scan (or `?scanId=` for a specific scan), with paging.
- **States**: no-active-scan, empty-candidate, unresolved-target, API 4xx/5xx and
  backend-unreachable states all render as explicit messages instead of crashes.
- **Backend authority preserved**: `RESOLVED`/`INFERRED`/`UNRESOLVED`, ordering, evidence
  chains, ambiguity, access kind, directness and truncation are displayed exactly as the
  backend returns them. The UI contains no relationship inference, confidence
  calculation, traversal logic or database access; `components/*` take backend payloads
  as props and `lib/view.ts` only interleaves nodes/edges, shortens display labels and
  formats timestamps.

## Backend API additions

None. All pages use existing read-only endpoints: `/api/symbols/search`, `/api/symbols/detail`,
`/api/symbols/usages`, `/api/entry-points`, `/api/entry-points/trace`,
`/api/relationships/trace`, `/api/relationships/database-tables`,
`/api/relationships/table-usages`, `/api/scans`, `/api/scans/{id}`. There is no REST
endpoint that lists database tables standalone (only the Slice 6 MCP tool), so tables are
reached from `/tables?component=`, from trace results and from symbol search; no endpoint
was added for it.

## Known limitations

- One new dev dependency (`vitest`) was added because the frontend had no test runner.
  Tests render components and async server-component pages with `react-dom/server` in a
  Node environment; there is no DOM, no browser automation and no client-side interaction
  coverage.
- All navigation uses `next/link` but the pages are server-rendered per request
  (`force-dynamic`, `fetch` with `no-store`); there are no client components and no
  optimistic/streamed updates.
- The backend base URL is server-side configuration (`LEGACY_API_BASE_URL`, default
  `http://127.0.0.1:8080`). The UI does not start scans; the scan page is read-only and
  new scans are still triggered with `POST /api/scans`.
- Next.js does not URL-decode dynamic route segments, so `/symbols/[id]` and
  `/tables/[id]` decode the segment explicitly; IDs are encoded with `encodeURIComponent`
  in links. This was verified in the running build for a route ID containing `/`.
- Free-text bounds in the forms are clamped to the backend's accepted ranges instead of
  surfacing a 400; backend validation errors are still shown if a request is rejected.
- No graph visualization, no AI summaries, no MCP client in the browser, no editing or
  refactoring features, and no Grails support were added.
- The end-to-end smoke used a local stub backend plus `next start`, not the real backend
  with PostgreSQL; the existing backend suite and Compose/MCP validation were not re-run
  per the slice instructions.

No unresolved Slice 7 acceptance blocker remains.
