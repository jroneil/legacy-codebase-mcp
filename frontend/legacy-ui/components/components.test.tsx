import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { FreshnessPanel } from "@/components/Freshness";
import { Pagination } from "@/components/Pagination";
import { RelationshipList } from "@/components/RelationshipList";
import { CandidateList, SymbolResults } from "@/components/SymbolResults";
import { TableImpactList } from "@/components/TableImpactList";
import { TraceView } from "@/components/TraceView";
import { asChain } from "@/lib/view";
import { candidate, edge, freshness, impact, page, path, symbol } from "../test/fixtures";

const render = (element: Parameters<typeof renderToStaticMarkup>[0]) => renderToStaticMarkup(element);

describe("symbol search results", () => {
  it("lists every backend candidate with its kind, state and location", () => {
    const html = render(
      <SymbolResults
        symbols={[
          symbol({ stableId: "one:Shared", simpleName: "Shared", kind: "CLASS", resolutionState: "RESOLVED", sourcePath: "src/One.java", startLine: 4 }),
          symbol({ stableId: "two:Shared", simpleName: "Shared", kind: "INTERFACE", resolutionState: "INFERRED", sourcePath: "src/Two.java", startLine: 9 }),
        ]}
      />,
    );
    expect(html).toContain("one:Shared");
    expect(html).toContain("two:Shared");
    expect(html).toContain("INTERFACE");
    expect(html).toContain("INFERRED");
    expect(html).toContain("src/Two.java:9");
    expect(html).toContain('href="/symbols/one%3AShared"');
    expect(html).toContain('href="/symbols/two%3AShared"');
  });

  it("shows an empty state instead of inventing a match", () => {
    expect(render(<SymbolResults symbols={[]} />)).toContain("No symbols matched");
  });
});

describe("ambiguous candidate selection", () => {
  it("preserves all candidates and asks the developer to choose", () => {
    const html = render(
      <CandidateList candidates={[candidate({ stableId: "one:Shared" }), candidate({ stableId: "two:Shared" })]} truncated />,
    );
    expect(html).toContain("one:Shared");
    expect(html).toContain("two:Shared");
    expect(html).toContain("did not select one");
    expect(html).toContain("Candidate list truncated");
  });
});

describe("relationships", () => {
  it("renders type, resolution state and evidence location", () => {
    const html = render(
      <RelationshipList
        relationships={[edge({ type: "WIRES_TO", resolutionState: "INFERRED", sourcePath: "applicationContext.xml", line: 18, column: 7 })]}
        emptyMessage="none"
      />,
    );
    expect(html).toContain("WIRES_TO");
    expect(html).toContain("INFERRED");
    expect(html).toContain("applicationContext.xml:18:7");
    expect(html).toContain("demo.CustomerService");
  });

  it("keeps unresolved targets as descriptions rather than links", () => {
    const html = render(
      <RelationshipList
        relationships={[edge({ targetId: null, targetDescription: "candidates: demo.CustomerDAO", resolutionState: "UNRESOLVED" })]}
        emptyMessage="none"
      />,
    );
    expect(html).toContain("candidates: demo.CustomerDAO");
    expect(html).toContain("UNRESOLVED");
    expect(html).not.toContain('href="/symbols/null"');
  });

  it("shows the provided empty message", () => {
    expect(render(<RelationshipList relationships={[]} emptyMessage="No outgoing relationships indexed." />)).toContain(
      "No outgoing relationships indexed.",
    );
  });
});

describe("trace rendering", () => {
  it("renders an ordered chain with per-edge evidence and the path state", () => {
    const chain = asChain(
      path({
        nodes: ["/customer/search", "java:type:demo.CustomerAction"],
        evidence: [edge({ type: "ROUTES_TO", resolutionState: "INFERRED", sourcePath: "web/WEB-INF/struts-config.xml", line: 42, column: 3 })],
        resolutionState: "INFERRED",
        termination: "STEP",
      }),
    );
    const html = render(<TraceView chains={[chain]} emptyMessage="none" />);
    expect(html).toContain("ROUTES_TO");
    expect(html).toContain("demo.CustomerAction");
    expect(html).toContain("web/WEB-INF/struts-config.xml:42:3");
    expect(html).toContain("INFERRED");
    expect(html).toContain("STEP");
  });

  it("states explicitly when the traversal was truncated", () => {
    const html = render(<TraceView chains={[asChain(path())]} truncated truncationReasons={["DEPTH_LIMIT"]} emptyMessage="none" />);
    expect(html).toContain("Truncated");
    expect(html).toContain("traversal depth bound reached");
  });

  it("shows an empty state when nothing matched", () => {
    expect(render(<TraceView chains={[]} emptyMessage="No path matched from this component." />)).toContain(
      "No path matched from this component.",
    );
  });
});

describe("table impact", () => {
  it("distinguishes read, write and mapping as well as direct and transitive", () => {
    const html = render(
      <TableImpactList
        impacts={[
          impact({ tableId: "db:table:CUSTOMER", access: "READ", direct: true }),
          impact({
            tableId: "db:table:ORDERS",
            access: "WRITE",
            direct: false,
            path: {
              nodes: ["java:type:demo.CustomerAction", "java:type:demo.CustomerDAO", "db:table:ORDERS"],
              evidence: [edge(), edge({ type: "WRITES_TABLE", resolutionState: "INFERRED", sourcePath: "src/demo/CustomerDAO.java", line: 42 })],
              resolutionState: "INFERRED",
              termination: "STEP",
            },
          }),
          impact({ tableId: "db:table:LEGACY", access: "MAPPING", direct: true }),
        ]}
      />,
    );
    expect(html).toContain("CUSTOMER");
    expect(html).toContain("ORDERS");
    expect(html).toContain("READ");
    expect(html).toContain("WRITE");
    expect(html).toContain("MAPPING");
    expect(html).toContain("direct");
    expect(html).toContain("transitive");
    expect(html).toContain("supporting evidence path");
    expect(html).toContain("CustomerDAO.java:42");
  });

  it("shows truncation and the empty state", () => {
    expect(render(<TableImpactList impacts={[impact()]} truncated truncationReasons={["RESULT_LIMIT"]} />)).toContain("result count bound reached");
    expect(render(<TableImpactList impacts={[]} />)).toContain("No database table impact");
  });
});

describe("scan freshness and paging", () => {
  it("renders the four freshness values provided by the backend", () => {
    const html = render(<FreshnessPanel freshness={freshness()} />);
    expect(html).toContain("11111111-1111-1111-1111-111111111111");
    expect(html).toContain("b".repeat(40));
    expect(html).toContain("database-usage-index-4");
    expect(html).toContain("2026-10-01T12:00:00Z");
  });

  it("renders the no-active-scan state when freshness is null", () => {
    const html = render(<FreshnessPanel freshness={null} />);
    expect(html).toContain("No active completed scan");
  });

  it("reports bounded results and links to the next page", () => {
    const html = render(
      <Pagination page={page([symbol()], { totalCount: 150, limit: 1, offset: 0, truncated: true })} basePath="/" params={{ q: "Customer" }} />,
    );
    expect(html).toContain("Showing 1–1 of 150");
    expect(html).toContain("Truncated: more results exist.");
    expect(html).toContain("offset=1");
  });
});
