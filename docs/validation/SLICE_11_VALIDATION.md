# Slice 11 — Spring MVC Mapping validation

Date: 2026-10-02. Base commit: `bdc98ed`. Changes are uncommitted.

## Files changed

- `.env.example`, `docker-compose.yml`, `README.md`
- `backend/legacy/src/main/java/com/oneil/legacy/framework/{FrameworkIndexer,FrameworkQueries,SpringMvcIndexer}.java`
- `backend/legacy/src/main/java/com/oneil/legacy/{mcp/McpTools,scan/ScanProperties}.java`
- `backend/legacy/src/main/resources/application.properties`
- `backend/legacy/src/test/java/com/oneil/legacy/framework/{SpringMvcIndexerTests,SpringMvcIntegrationTests}.java`
- `backend/legacy/src/test/java/com/oneil/legacy/{database/DatabaseIntegrationTests,grails/GrailsIntegrationTests}.java`
- `backend/legacy/src/test/resources/fixtures/spring-mvc/**`
- `frontend/legacy-ui/app/entry-points/page.tsx`,
  `frontend/legacy-ui/components/components.test.tsx`, and
  `frontend/legacy-ui/test/fixtures.ts`
- `docs/validation/SLICE_11_VALIDATION.md`

No dependency, schema, migration, REST endpoint, or MCP tool was added.

## Commands and results

| Command | Result |
| --- | --- |
| `cd backend/legacy && ./mvnw -Dtest=SpringMvcIndexerTests,SpringMvcIntegrationTests test` | 9 passed, 0 failed |
| `cd backend/legacy && ./mvnw -Dtest=FrameworkIndexerTests,FrameworkIntegrationTests,GrailsIndexerTests,GrailsIntegrationTests,Grails26IndexerTests,Grails26IntegrationTests,DatabaseIndexerTests,DatabaseIntegrationTests,McpRestEquivalenceTests test` | 92 passed, 0 failed |
| `cd backend/legacy && ./mvnw test` | 185 passed, 0 failed; build success |
| `docker run --rm -v .../frontend/legacy-ui:/app -w /app node:24-bookworm-slim sh -lc 'npm test && npm run build'` | 50 passed, 0 failed; Next.js production build passed |

## Supported baseline and rules

- Source-only detection covers imported or fully qualified `@Controller` and
  `@RestController`; class/method `@RequestMapping`; and
  `@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping`, and
  `@PatchMapping`.
- `value`/`path`, strings, arrays, string concatenation, and bounded
  `static final String` references are evaluated without loading target classes.
  Class and method paths are composed deterministically. Explicit request methods are
  retained; absent methods remain `UNSPECIFIED`.
- A route reuses its indexed Java method through `ROUTES_TO`. Literal String view
  returns use `RENDERS`; `@ResponseBody`, `@RestController`, redirects, and
  forwards do not create views.
- Route IDs contain source path, HTTP method, composed path, Java method identity, and
  annotation location. Duplicate mappings remain separate candidates. Dynamic values
  remain visible as `UNRESOLVED` with localized evidence and diagnostics.
- Every route edge records source path, line, column, evidence type, and resolution
  state. Snapshot publication and weakest-edge path confidence are unchanged.
- The bounded XML case recognizes an explicitly declared
  `BeanNameUrlHandlerMapping` and slash-named controller bean, then links its unique
  `handleRequest` method as `INFERRED` XML convention evidence.

## Acceptance evidence

The focused fixture proves:

```text
GET /customers/{id}
  -> CustomerController.getCustomer(String)
  -> CustomerServiceImpl.find(String)
  -> CustomerDAO.find(String)
  -> READS_TABLE CUSTOMER
```

and:

```text
POST /customers
  -> CustomerController.create(String)
  -> CustomerServiceImpl.create(String)
  -> CustomerDAO.insert(String)
  -> WRITES_TABLE CUSTOMER
```

Both paths are `RESOLVED` and carry source evidence. Focused tests also prove constant
and array paths, all shortcut methods, explicit `RequestMethod` arrays, unspecified
methods, duplicate candidates, unresolved expressions, deterministic IDs, view
rendering/no-view response behavior, malformed Java localization, XML mapping, and
REST/MCP entry-point and traversal equivalence. The 92-test regression run covers
Struts 1, Spring XML wiring, Grails, database analysis, and MCP/REST equivalence.

The framework-neutral default analyzer version is now `legacy-analyzer-1` in Java
configuration, application properties, Compose, `.env.example`, README, and fixtures.

## Known limitations

- Annotation injection and component scanning are not indexed; direct Java calls and
  existing explicit Spring XML `INJECTS`/`WIRES_TO` evidence remain usable.
- Legacy XML support is limited to `BeanNameUrlHandlerMapping` with a unique
  `handleRequest` method. `SimpleUrlHandlerMapping`, the historical handler-adapter
  matrix, and XML annotation-handler discovery are not modeled.
- Mapping evaluation does not perform general Java data flow or execute annotation code.
  Unsupported/dynamic expressions remain `UNRESOLVED`.
- View extraction is limited to direct String literal returns.

No Slice 11 acceptance blocker remains.
