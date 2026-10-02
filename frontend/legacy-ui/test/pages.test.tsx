import { afterEach, describe, expect, it, vi } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import SearchPage from "@/app/page";
import SymbolPage from "@/app/symbols/[id]/page";
import EntryPointsPage from "@/app/entry-points/page";
import EntryPointTracePage from "@/app/entry-points/trace/page";
import TracePage from "@/app/trace/page";
import TableImpactPage from "@/app/tables/page";
import TablePage from "@/app/tables/[id]/page";
import ScanPage from "@/app/scan/page";
import ErrorsPage from "@/app/errors/page";
import {
  analysisError,
  answer,
  candidate,
  edge,
  entryPoint,
  freshness,
  impact,
  page,
  path,
  scan,
  scanDetail,
  stubFetch,
  symbol,
  traversal,
} from "./fixtures";

const params = (values: Record<string, string> = {}) => Promise.resolve(values);
const idParams = (id: string) => Promise.resolve({ id });

function mock(routes: Record<string, unknown>, statuses: Record<string, number> = {}) {
  vi.stubGlobal("fetch", stubFetch(routes, statuses));
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("search page", () => {
  it("renders matches, scan freshness and paging", async () => {
    mock({ "/api/symbols/search": { freshness: freshness(), candidates: page([symbol()], { totalCount: 1 }) } });
    const html = renderToStaticMarkup(await SearchPage({ searchParams: params({ q: "Customer" }) }));
    expect(html).toContain("CustomerAction");
    expect(html).toContain("11111111-1111-1111-1111-111111111111");
    expect(html).toContain("Showing 1–1 of 1");
  });

  it("keeps every ambiguous candidate selectable", async () => {
    mock({
      "/api/symbols/search": {
        freshness: freshness(),
        candidates: page([symbol({ stableId: "one:Shared" }), symbol({ stableId: "two:Shared" })]),
      },
    });
    const html = renderToStaticMarkup(await SearchPage({ searchParams: params({ q: "Shared" }) }));
    expect(html).toContain('href="/symbols/one%3AShared"');
    expect(html).toContain('href="/symbols/two%3AShared"');
  });

  it("shows the no-active-scan state instead of results", async () => {
    mock({ "/api/symbols/search": { freshness: null, candidates: page([]) } });
    const html = renderToStaticMarkup(await SearchPage({ searchParams: params({ q: "Customer" }) }));
    expect(html).toContain("No active completed scan");
  });

  it("shows the backend error when the API fails", async () => {
    mock({ "/api/symbols/search": { error: "Scan persistence is unavailable." } }, { "/api/symbols/search": 503 });
    const html = renderToStaticMarkup(await SearchPage({ searchParams: params({ q: "Customer" }) }));
    expect(html).toContain('role="alert"');
    expect(html).toContain("Scan persistence is unavailable.");
  });
});

describe("symbol detail page", () => {
  it("renders identity, outgoing and incoming relationships with evidence", async () => {
    mock({
      "/api/symbols/detail": {
        freshness: freshness(),
        symbol: symbol(),
        outgoing: page([edge({ type: "CALLS", resolutionState: "INFERRED", sourcePath: "src/demo/CustomerAction.java", line: 12, column: 5 })]),
      },
      "/api/symbols/usages": {
        freshness: freshness(),
        usages: page([
          edge({
            sourceId: "/customer/search",
            targetId: "java:type:demo.CustomerAction",
            type: "ROUTES_TO",
            resolutionState: "RESOLVED",
            sourcePath: "web/WEB-INF/struts-config.xml",
            line: 6,
            column: 1,
          }),
        ]),
      },
    });
    const html = renderToStaticMarkup(await SymbolPage({ params: idParams("java:type:demo.CustomerAction"), searchParams: params() }));
    expect(html).toContain("java:type:demo.CustomerAction");
    expect(html).toContain("demo.CustomerAction");
    expect(html).toContain("src/demo/CustomerAction.java:1-60");
    expect(html).toContain("CALLS");
    expect(html).toContain("INFERRED");
    expect(html).toContain("src/demo/CustomerAction.java:12:5");
    expect(html).toContain("ROUTES_TO");
    expect(html).toContain("web/WEB-INF/struts-config.xml:6:1");
  });

  it("shows an error notice for an unknown stable ID", async () => {
    mock({ "/api/symbols/detail": { error: "Symbol not found in an active completed scan." } }, { "/api/symbols/detail": 404 });
    const html = renderToStaticMarkup(await SymbolPage({ params: idParams("java:type:demo.Missing"), searchParams: params() }));
    expect(html).toContain("Symbol not found in an active completed scan.");
  });

  it("decodes an encoded route-ID segment before querying the API", async () => {
    const calls: URL[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = input instanceof URL ? input : new URL(String(input));
        calls.push(url);
        const stableId = url.pathname === "/api/symbols/detail" ? (url.searchParams.get("stableId") ?? "") : "";
        const body =
          url.pathname === "/api/symbols/detail"
            ? { freshness: freshness(), symbol: symbol({ stableId, simpleName: stableId, qualifiedName: stableId }), outgoing: page([]) }
            : { freshness: freshness(), usages: page([]) };
        return new Response(JSON.stringify(body), { status: 200, headers: { "content-type": "application/json" } });
      }),
    );
    const html = renderToStaticMarkup(await SymbolPage({ params: idParams("%2Fcustomer%2Fsearch"), searchParams: params() }));
    expect(calls.find((url) => url.pathname === "/api/symbols/detail")?.searchParams.get("stableId")).toBe("/customer/search");
    expect(html).toContain("/customer/search");
  });
});

describe("entry points", () => {
  it("lists routes with their declaration location and a trace link", async () => {
    mock({ "/api/entry-points": { freshness: freshness(), candidates: page([entryPoint()]) } });
    const html = renderToStaticMarkup(await EntryPointsPage({ searchParams: params({ path: "" }) }));
    expect(html).toContain("/customer/search");
    expect(html).toContain("web/WEB-INF/struts-config.xml:6");
    expect(html).toContain("operation=search");
    expect(html).toContain("/entry-points/trace?entryId=%2Fcustomer%2Fsearch");
  });

  it("renders the configuration path for a selected entry point", async () => {
    mock({
      "/api/entry-points/trace": {
        freshness: freshness(),
        entryId: "/customer/search",
        paths: [
          {
            components: ["/customer/search", "java:type:demo.CustomerAction"],
            evidence: [edge({ type: "ROUTES_TO", resolutionState: "INFERRED", sourcePath: "web/WEB-INF/struts-config.xml", line: 6, column: 1 })],
            resolutionState: "INFERRED",
            termination: "STEP",
          },
        ],
        truncated: false,
      },
    });
    const html = renderToStaticMarkup(await EntryPointTracePage({ searchParams: params({ entryId: "/customer/search" }) }));
    expect(html).toContain("ROUTES_TO");
    expect(html).toContain("demo.CustomerAction");
    expect(html).toContain("web/WEB-INF/struts-config.xml:6:1");
    expect(html).toContain("INFERRED");
  });
});

describe("trace page", () => {
  it("renders bounded paths with confidence, evidence and truncation reasons", async () => {
    mock({
      "/api/relationships/trace": answer({
        candidates: [candidate({ stableId: "java:type:demo.CustomerAction" })],
        traversal: traversal({
          paths: [
            path({
              nodes: ["java:type:demo.CustomerAction", "db:table:CUSTOMER"],
              evidence: [edge({ type: "READS_TABLE", resolutionState: "RESOLVED", sourcePath: "src/demo/CustomerDAO.java", line: 40, column: 9 })],
              resolutionState: "RESOLVED",
              termination: "STEP",
            }),
          ],
          truncated: true,
          truncationReasons: ["DEPTH_LIMIT"],
        }),
      }),
    });
    const html = renderToStaticMarkup(await TracePage({ searchParams: params({ component: "CustomerAction", direction: "OUTGOING" }) }));
    expect(html).toContain("READS_TABLE");
    expect(html).toContain("src/demo/CustomerDAO.java:40:9");
    expect(html).toContain("Truncated");
    expect(html).toContain("traversal depth bound reached");
  });

  it("shows candidates instead of picking one when the selection is ambiguous", async () => {
    mock({
      "/api/relationships/trace": answer({
        selection: "AMBIGUOUS",
        candidates: [candidate({ stableId: "one:Shared" }), candidate({ stableId: "two:Shared" })],
        traversal: null,
      }),
    });
    const html = renderToStaticMarkup(await TracePage({ searchParams: params({ component: "Shared" }) }));
    expect(html).toContain("one:Shared");
    expect(html).toContain("two:Shared");
    expect(html).toContain("did not select one");
  });

  it("prompts for a component when none is supplied", async () => {
    mock({});
    const html = renderToStaticMarkup(await TracePage({ searchParams: params() }));
    expect(html).toContain("Enter a component");
  });
});

describe("table impact pages", () => {
  it("renders read/write access and direct/transitive paths for a component", async () => {
    mock({
      "/api/relationships/database-tables": answer({
        tables: [
          impact({ tableId: "db:table:CUSTOMER", access: "READ", direct: true }),
          impact({
            tableId: "db:table:ORDERS",
            access: "WRITE",
            direct: false,
            path: {
              nodes: ["java:type:demo.CustomerAction", "java:type:demo.CustomerDAO", "db:table:ORDERS"],
              evidence: [
                edge({ type: "CALLS", resolutionState: "INFERRED" }),
                edge({ type: "WRITES_TABLE", resolutionState: "INFERRED", sourcePath: "src/demo/CustomerDAO.java", line: 42, column: 2 }),
              ],
              resolutionState: "INFERRED",
              termination: "STEP",
            },
          }),
        ],
        traversal: traversal({ paths: [], truncated: true, truncationReasons: ["RESULT_LIMIT"] }),
      }),
    });
    const html = renderToStaticMarkup(await TableImpactPage({ searchParams: params({ component: "/customer/search" }) }));
    expect(html).toContain("READ");
    expect(html).toContain("WRITE");
    expect(html).toContain("direct");
    expect(html).toContain("transitive");
    expect(html).toContain("src/demo/CustomerDAO.java:42:2");
    expect(html).toContain("result count bound reached");
  });

  it("lists components that use a table", async () => {
    mock({
      "/api/relationships/table-usages": answer({
        direction: "INCOMING",
        tables: [impact({ tableId: "db:table:CUSTOMER", componentId: "java:type:demo.CustomerDAO", access: "WRITE", direct: true })],
        traversal: traversal({ paths: [] }),
      }),
    });
    const html = renderToStaticMarkup(await TablePage({ params: idParams("db:table:CUSTOMER"), searchParams: params() }));
    expect(html).toContain("db:table:CUSTOMER");
    expect(html).toContain("java:type:demo.CustomerDAO");
    expect(html).toContain("/trace?component=java%3Atype%3Ademo.CustomerDAO");
  });
});

describe("scan and error pages", () => {
  const id = "11111111-1111-1111-1111-111111111111";

  it("renders repository-root children without a scan button", async () => {
    mock({
      "/api/repositories": {
        path: "",
        parent: null,
        items: [
          { id: "legacy", name: "legacy" },
          { id: "other", name: "other" },
        ],
        totalCount: 2,
        truncated: false,
      },
      "/api/scans": { activeScanId: id, scans: page([scan()]) },
      ["/api/scans/" + id]: scanDetail(),
    });
    const html = renderToStaticMarkup(await ScanPage());
    expect(html).toContain("Browse repositories");
    expect(html).toContain('href="/scan?path=legacy"');
    expect(html).toContain('href="/scan?path=other"');
    expect(html).toContain("Open a child folder");
    expect(html).not.toContain("Scan this repository");
    expect(html).toContain("COMPLETED");
    expect(html).toContain("active");
  });

  it("renders nested navigation, Up, and an explicit scan-current-folder form", async () => {
    mock({
      "/api/repositories": {
        path: "legacy",
        parent: "",
        items: [
          { id: "legacy/grails-app", name: "grails-app" },
          { id: "legacy/struts-app", name: "struts-app" },
        ],
        totalCount: 2,
        truncated: false,
      },
      "/api/scans": { activeScanId: null, scans: page([]) },
    });
    const html = renderToStaticMarkup(
      await ScanPage({ searchParams: params({ path: "legacy", scanId: id, status: "COMPLETED" }) }),
    );
    expect(html).toContain("Current folder");
    expect(html).toContain('href="/scan"');
    expect(html).toContain(">Up<");
    expect(html).toContain('href="/scan?path=legacy%2Fgrails-app"');
    expect(html).toContain('name="repository" value="legacy"');
    expect(html).toContain("Selected repository:");
    expect(html).toContain("Scan this repository");
    expect(html).toContain("completed and is now active");
  });

  it("lets an empty nested folder remain the selected scan target", async () => {
    mock({
      "/api/repositories": {
        path: "other/sample-app",
        parent: "other",
        items: [],
        totalCount: 0,
        truncated: false,
      },
      "/api/scans": { activeScanId: null, scans: page([]) },
    });
    const html = renderToStaticMarkup(await ScanPage({ searchParams: params({ path: "other/sample-app" }) }));
    expect(html).toContain("This folder has no visible child directories.");
    expect(html).toContain('href="/scan?path=other"');
    expect(html).toContain('name="repository" value="other/sample-app"');
    expect(html).toContain("Scan this repository");
  });

  it("shows stale-path recovery, backend discovery errors, and scan-action errors", async () => {
    mock(
      {
        "/api/repositories": { error: "Invalid repository path." },
        "/api/scans": { activeScanId: null, scans: page([]) },
      },
      { "/api/repositories": 400 },
    );
    const html = renderToStaticMarkup(
      await ScanPage({ searchParams: params({ path: "removed/repository", error: "Scan request failed." }) }),
    );
    expect(html).toContain("Invalid request: Invalid repository path.");
    expect(html).toContain("Return to repository root");
    expect(html).toContain("Scan request failed.");
  });

  it("renders analysis errors with file, stage, state and message", async () => {
    mock({
      "/api/scans": { activeScanId: id, scans: page([scan()]) },
      [`/api/scans/${id}`]: scanDetail({ errors: page([analysisError()]) }),
    });
    const html = renderToStaticMarkup(await ErrorsPage({ searchParams: params() }));
    expect(html).toContain("src/demo/Broken.java");
    expect(html).toContain("java-index");
    expect(html).toContain("PARSE_ERROR");
    expect(html).toContain("UNRESOLVED");
    expect(html).toContain("Source file could not be parsed");
  });

  it("shows a no-active-scan state", async () => {
    mock({ "/api/scans": { activeScanId: null, scans: page([]) } });
    const html = renderToStaticMarkup(await ErrorsPage({ searchParams: params() }));
    expect(html).toContain("No active completed scan");
  });

  it("shows an API error state for an unknown scan", async () => {
    mock({ "/api/scans/does-not-exist": { error: "Scan not found." } }, { "/api/scans/does-not-exist": 404 });
    const html = renderToStaticMarkup(await ErrorsPage({ searchParams: params({ scanId: "does-not-exist" }) }));
    expect(html).toContain("Scan not found.");
  });
});
