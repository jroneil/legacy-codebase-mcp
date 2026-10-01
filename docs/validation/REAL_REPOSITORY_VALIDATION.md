# Real Legacy Repository Validation

Date: 2026-10-01. Base commit: `086fa9e`. Target repository: **`weblegacy/struts1`**, commit
`a2b7d6a690d5f2b481daa4b4a5d4de490d1b6718`, cloned read-only (shallow) to
`/tmp/legacy-validation/struts1`. The target repository was not modified, and no target build
script or application code was executed.

## Repository/framework profile

- Real Apache Struts 1.x codebase (the maintained `weblegacy` fork): the framework itself
  (`core`, `taglib`, `tiles`, `tiles2`, `extras`, `faces`, `el`, `scripting`, `integration`,
  `assembly`) plus 9 complete example applications (`blank`, `cookbook`, `el-example`,
  `examples`, `faces-example1/2`, `mailreader`, `mailreader-dao`, `scripting-mailreader`).
- Size: **671 Java**, 209 XML, 166 JSP, 87 `.properties`; 23 `pom.xml`; 21 `struts-config*.xml`;
  8 `web.xml`; 21 MB working tree.
- Framework mix: Struts 1.x config XML (`struts-config`, `web.xml`, forwards, forms, wildcard
  and dispatch mappings), plain Java, JSP views, JDBC-free DAO layer
  (`mailreader-dao` is an **in-memory** DAO: `UserDatabase`/`MemoryUserDatabase`).
- **Not present:** Spring (no `applicationContext*.xml`, no `@Autowired`), Grails/Groovy,
  Hibernate (no `*.hbm.xml`), and **no database access at all** — no SQL statements, no JDBC,
  no datasource, no `.sql` files. All `jdbc`/`datasource` text in the repository is historical
  release-note prose.
- Build: Maven multi-module reactor; **not built or executed** (target build execution is out of
  scope and was not needed). The analyzer works from source only, so build state does not affect
  the scan.
- Applicable analyzer capabilities: Java symbol index, Struts/Spring-XML framework indexer
  (`web.xml`, `struts-config*.xml`, action/form/forward/view wiring), relationship traversal,
  REST and MCP interfaces, `inspect_location`. The database indexer applies only to its SQL
  detection path (see the defect below); table analysis is **untestable on this target**.

## Selected validation questions

| # | Question (manually traced) |
| --- | --- |
| V1 | Which Action, ActionForm and forward does route `/SubmitLogon` (mailreader) use? |
| V1b | `input="Logon"` in a config with `<controller inputForward="true"/>` — is it a path or a forward name? |
| V2 | What does `/MainMenu` reach, and which view does it render? |
| V3 | Which methods does the DispatchAction route `/dispatch-submit` (examples) dispatch to? |
| V3b | Do Mapping/Lookup dispatch routes get request-parameter dispatch candidates? (must not) |
| V4 | The mailreader config declares `/Welcome` twice — is the ambiguity preserved? |
| V5 | What does `BaseAction.doGetUser` call on the DAO layer, and where? |
| V5b | Does `MemoryUserDatabase` implement `UserDatabase`? |
| V6 | Where is `LogonAction` used? |
| V7 | `inspect_location` at `BaseAction.java:254` — containing symbol and evidence? |
| V8a | The abstract mapping `//BaseAction` has `type="…{1}Action"` — is a class invented? |
| V8b | `/SaveSubscription` forwards to `/EditRegistration.do` while only wildcard `/Edit*` exists — resolved or not? |
| V9 | Do MCP tools return the same evidence as REST? |
| V10 | Are bounded results and truncation reported? |
| V11 | Are the reported analysis errors genuine? |
| V12 | Is the string constant `"Delete"` treated as SQL? |

## Expected vs actual results

| # | Expected (manual, from source/config) | Actual | Verdict |
| --- | --- | --- | --- |
| V1 | `ROUTES_TO`→`LogonAction` RESOLVED; `WIRES_TO`→form `LogonForm` RESOLVED; forward `Success`→route `/MainMenu` RESOLVED | exactly that, at `struts-config.xml:144/151` | 3 TP |
| V1b | `input` is a forward *name* (`ControllerConfig.inputForward=false` default; mailreader sets `inputForward="true"`, DTD confirms) | emitted `FORWARDS_TO`→path `Logon` (no such action), UNRESOLVED | **1 FP**; route→forward-`Logon` link missing (1 FN). Documented limitation |
| V2 | `/MainMenu`→`MainMenuAction` RESOLVED; forward `Success`→`RENDERS` `MainMenu.jsp` RESOLVED | exactly that (RENDERS on the FORWARD symbol) | 2 TP |
| V3 | `/dispatch-submit`→class RESOLVED + `doFoo`,`doBar` INFERRED (`parameter="dispatchMethod"`) | **before fix:** class only (2 FN). **after fix:** 3 edges, descriptions `request parameter dispatchMethod=doFoo/doBar` | 3 TP after fix |
| V3b | No request-parameter dispatch candidates for `/mapping-foo`, `/lookup*` | none | 0 FP |
| V4 | Ambiguity preserved, both mailreader `/Welcome` routes distinguishable | `selection=AMBIGUOUS`, 4 candidates incl. `…#/Welcome@124:41` | 1 TP |
| V5 | `BaseAction.java:254 database.findUser(username)`→`UserDatabase#findUser(String)` RESOLVED | exactly that, evidence `BaseAction.java:254` | 1 TP |
| V5b | `MemoryUserDatabase implements UserDatabase` RESOLVED | exactly that | 1 TP |
| V5c | 4-arg `doGetUser` call at line 286 resolves to the 4-arg overload | `CALLS UNRESOLVED` (`doGetUser/4`) | **1 FN** — documented incomplete-classpath limitation |
| V6 | Exactly one incoming edge to `LogonAction` (the route) | totalCount 1 | 1 TP |
| V7 | Containing symbol = 4-arg `doGetUser`; outgoing `CALLS`→`findUser` @254 | exactly that; span 239–266; enclosing `BaseAction` | 1 TP |
| V8a | No invented class for `{1}Action` | `ROUTES_TO` UNRESOLVED with raw type as description | 1 TP |
| V8b | Unresolved (wildcards not expanded) | UNRESOLVED, `candidates=[]` | 1 TP (honest) |
| V9 | MCP ≡ REST | `get_symbol` (7 edges), `find_usages` (1), `trace_component` (100 paths) identical, same `scanId` | 3 TP |
| V10 | Bounds and truncation reported | search `limit=1`: `truncated=true`, total 1657; trace `depth=1,limit=1`: `truncated=true`, `RESULT_LIMIT` | 2 TP |
| V11 | Errors are genuine | `INVALID_UTF8`: files are Latin-1 (`0xe9`), not UTF-8 ✓. `XML_REFERENCE_MISSING`: forwards to `dispatch.jsp`/`index.jsp` etc. that genuinely do not exist ✓ | 2 TP |
| V12 | `"Delete"` is a task token, not SQL | **before fix:** 4 bogus `QUERY_ARTIFACT` + 4 `SQL_PARSE` errors. **after fix:** 0 | 4 FP fixed |

Sample totals: **24 true positives, 1 outstanding false positive (4 edges), 2 false negatives
(1 fixed group of 8 edges + 1 classpath-limited)**. This is a 16-question sample; **no
whole-repository precision or recall is claimed.**

## True positives

Route/action/form/forward wiring (`/SubmitLogon`, `/MainMenu`), forward→JSP `RENDERS`,
duplicate-route ambiguity across a multi-app repository, DAO interface call resolution with
correct source line, DAO implementation binding, usage lookup, `inspect_location` on a real line,
honest `UNRESOLVED` for wildcard placeholders and unexpanded wildcard forwards, MCP/REST
equivalence, truncation reporting, and error legitimacy.

## False positives

1. **`inputForward` is not modeled (outstanding, 4 edges).** For the 4 configs that set
   `<controller inputForward="true"/>`, `input="Logon"`/`input="Input"` are forward names, but the
   analyzer interprets `input` as a module-relative path and emits an `UNRESOLVED` `FORWARDS_TO`
   edge (`Logon; candidates=[]; count=0`). No wrong target is claimed and the true forward is
   still present as a `CONTAINS` child, but the edge is spurious. The other 31 `input=`
   attributes repo-wide are in configs where `inputForward` is false, so their path
   interpretation is correct. Left as a documented limitation (supporting `inputForward` would
   add Struts configuration semantics beyond the current requirement).
2. **Bare SQL keyword strings were misdetected as queries (fixed).** `Constants.DELETE = "Delete"`
   and its two assignments produced 4 `QUERY_ARTIFACT` symbols, 4 `DECLARES_QUERY` edges and 4
   `SQL_PARSE` errors.

## False negatives

1. **`org.apache.struts.extras.actions.DispatchAction` was not recognized (fixed).** Struts 1.3
   moved `DispatchAction` to the `extras` module; the analyzer matched only
   `org.apache.struts.actions.DispatchAction`, so 4 real Dispatch/EventDispatch routes lost their
   INFERRED dispatch candidates (8 edges).
2. **Internal overload call `doGetUser(a,b,c,d)` at `BaseAction.java:286` stayed UNRESOLVED.**
   Downloaded jars are not on the parser classpath, so the sibling overloads' parameter type
   `jakarta.servlet.http.HttpServletRequest` is unresolved (`?HttpServletRequest`) and overload
   resolution fails. This is the documented "source-only plus JDK classpath" limitation, not a
   defect.
3. **Wildcard mappings are not expanded.** `/Save*`/`/Edit*`/`/*` and `//BaseAction` are indexed
   as declared, so forwards to `/EditRegistration.do` and the `{1}Action` type cannot resolve.
   Documented Slice 3 limitation.

## Unresolved / inferred cases

- `UNRESOLVED` relationships: **11,559** — dominated by references to third-party types absent
  from the classpath (`javax`/`jakarta.servlet`, `commons-*`): 8,729 `CALLS`, 1,950 `IMPORTS`,
  492 `FORWARDS_TO`, 232 `RENDERS`, 95 `EXTENDS`, 29 `ROUTES_TO`. This is expected for a
  framework repository scanned without its dependency jars and is correctly reported rather than
  guessed.
- `INFERRED`: **298** — 290 `OVERRIDES` (annotation-intent inference) plus the 8 dispatch
  candidates restored by the fix.
- `RESOLVED`: **20,510**.
- Ambiguity was preserved: cross-application duplicate route paths (`/Welcome`, `/SubmitLogon`)
  return candidate lists, and duplicate routes inside one config are distinguished by a
  `@line:column` suffix.

## Defects fixed

Both are narrow correctness defects in already-supported behaviour, each with a regression test.
No new parser, framework, UI, MCP tool or schema was added.

1. `FrameworkIndexer.dispatch()` now recognizes both request-parameter `DispatchAction`
   locations (`org.apache.struts.actions` and `org.apache.struts.extras.actions`), including the
   import-only form, and deliberately does **not** treat `MappingDispatchAction`/
   `LookupDispatchAction` chains as request-parameter dispatch (they select the method from
   configuration or a resource key). Regression test:
   `FrameworkIndexerTests.extrasDispatchActionIsRecognizedButMappingAndLookupDispatchAreNot`.
2. `DatabaseIndexer` now requires a SQL candidate to have a statement body
   (`looksLikeSql`), so a bare task token such as `"Delete"` is no longer reported as a query
   artifact or a parse error. Regression test:
   `DatabaseIndexerTests.bareKeywordTaskTokensAreNotTreatedAsSql`.

Measured effect on the target repository (before → after):

```text
analysis errors          37 -> 33   (SQL_PARSE 4 -> 0)
QUERY_ARTIFACT symbols    4 -> 0
DECLARES_QUERY edges      4 -> 0
symbols                8824 -> 8820
relationships         32363 -> 32367
INFERRED edges          290 -> 298  (+8 dispatch candidates)
```

## Commands/test counts

Backend commands ran in `backend/legacy`; the analyzer was exercised over HTTP.

| Command | Result |
| --- | --- |
| `./mvnw -Dmaven.test.skip=true package` | Passed; validated run used the rebuilt jar |
| `./mvnw -Dtest=FrameworkIndexerTests,DatabaseIndexerTests test` | 48 passed (targeted run after the fixes) |
| `./mvnw test` | Run once at completion: **172 passed**, 0 failures/errors/skips, BUILD SUCCESS (170 before the two regression tests) |
| `POST /api/scans` (×3) | COMPLETED each time against the real repository |
| REST queries | `/api/symbols/search`, `/api/symbols/detail`, `/api/symbols/usages`, `/api/relationships/trace`, `/api/entry-points`, `/api/scans/{id}` |
| MCP queries | `initialize`, `tools/call`: `get_symbol`, `find_usages`, `trace_component`, `inspect_location` |

Environment: PostgreSQL 17.11 (throwaway Compose project `slice9-validation`, port 54349),
backend on `127.0.0.1:18090`, `ANALYZER_VERSION=real-repo-validation-struts1[-fixed]`. The
frontend was not touched and was not rebuilt.

## Metrics for the validated scan (`e00566ad-9e5b-43aa-a34a-6308c75c949c`)

```text
scan duration            23.99 s (synchronous POST; 26.85 s and 25.06 s on the two earlier runs)
files inventoried        1133
symbols                  8820   (METHOD 4648, FIELD 2226, FORWARD 826, CLASS 642, ROUTE 237,
                                 PACKAGE 84, FORM 50, VIEW 39, INTERFACE 29, CONTEXT 29, SERVLET 10)
relationships            32367  (RESOLVED 20510, INFERRED 298, UNRESOLVED 11559)
analysis errors          33     (XML_REFERENCE_MISSING 16, INVALID_UTF8 15, JAVA_UNSUPPORTED_TYPE 2)
gitCommitSha             a2b7d6a690d5f2b481daa4b4a5d4de490d1b6718  (matches the clone's HEAD)
truncation               encountered and reported (list limits, candidate limits, RESULT_LIMIT)
```

## Performance observations

- ~1133 files in ~24–27 s (~21 files/s) on this workstation, single scan transaction, no
  batching tuning needed. Repeat scans of the same repository were within ~10% of each other.
- The dominant cost is JavaParser symbol solving across 671 files with a source-only classpath;
  the 11.5k unresolved references indicate the solver spends effort on unresolved third-party
  types. Loading the target's dependency jars would likely both raise `RESOLVED` counts and
  reduce scan time spent on failed resolutions — a future optimization, not attempted here.
- Traversal with `limit=100` returned 100 paths and reported truncation, so query cost is bounded
  by the documented engine budgets.
- 826 `FORWARD` symbols for 237 routes reflects the per-route instantiation of global forwards;
  it inflates symbol counts but preserves per-route evidence.

## Known limitations

- **No database coverage is possible on this target.** There is no SQL, JDBC, Hibernate, GORM or
  datasource anywhere in the repository, so the required "DAO/domain/database-table path",
  "database read" and "database write" questions could not be exercised. Table analysis remains
  validated only against the synthetic Slice 4/5/8 fixtures. A target with a real persistence
  layer is needed to complete that coverage.
- No Spring or Grails in the repository, so the "Spring/Grails injection path" item is not
  applicable (no `applicationContext*.xml`, no `grails-app`).
- `inputForward` (`<controller inputForward="true">`) is not modeled → 4 spurious `UNRESOLVED`
  `FORWARDS_TO` edges and a missing route→named-forward link.
- Only `org.apache.struts.actions`/`org.apache.struts.extras.actions` request-parameter
  `DispatchAction` variants are recognized. `MappingDispatchAction` (configuration-selected
  method) and `LookupDispatchAction` (resource-key map) produce no dispatch candidates, and the
  `parameter` map syntax (`doFoo,bar=doBar`) is reported verbatim in the edge description.
- Wildcard paths (`/*`, `/Save*`, `/{1}Action`) are indexed as declared and not expanded, so some
  forwards and the abstract base mapping stay `UNRESOLVED`.
- With a source-only classpath, 11.5k references to absent third-party types are `UNRESOLVED`;
  internal overload resolution can fail when a sibling overload uses an unavailable type.
- Provenance lines for multi-line XML start tags point at the **last** line of the start tag
  (SAX locator behaviour), e.g. the `/SubmitLogon` action element reports line 144 although it
  begins at 137. Values are always inside the element but are not the element's first line.
- Symbol search matches simple/qualified names and exact stable IDs, not stable-ID substrings.
- One scan per configured repository root; no incremental re-scan, so the whole 1133-file tree is
  re-analysed on every scan.
- Sample size is 16 questions; no precision/recall claim is made for the whole repository.

## Overall conclusion

For this repository the analyzer is **useful and trustworthy for what it models, provided its
limitations are respected**:

- It correctly and reproducibly recovered the Struts 1.x wiring that matters — route → Action →
  form → forward → view — with exact evidence locations, and it kept genuine ambiguity
  (`/Welcome`, `/SubmitLogon` across apps) instead of guessing.
- It resolved the real DAO path (Action base class → `UserDatabase` interface method) with
  correct file/line evidence, and `inspect_location` pinpointed the containing method from a raw
  source line.
- MCP and REST returned identical evidence for the same query, and every bounded response
  reported truncation.
- Uncertainty is handled honestly: absent third-party types and unexpanded wildcards are
  `UNRESOLVED` rather than guessed, and inference is confined to `OVERRIDES` and dispatch
  candidates.
- Two real defects were found and fixed (extras `DispatchAction` false negative, bare-keyword
  SQL false positive); the remaining false positive is a documented unmodeled Struts attribute.

It is **not** validated for database/table analysis on this repository, and it does not claim
build, runtime, Spring-injection or Grails behaviour here. Completing that requires a target with
a persistence layer.
