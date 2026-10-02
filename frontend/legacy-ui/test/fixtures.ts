import type {
  AnalysisError,
  Answer,
  Candidate,
  EntryPoint,
  Freshness,
  Page,
  Relationship,
  Scan,
  ScanDetail,
  Symbol,
  TableImpact,
  TraversalPath,
  TraversalResult,
} from "@/lib/api";

export function freshness(overrides: Partial<Freshness> = {}): Freshness {
  return {
    scanId: "11111111-1111-1111-1111-111111111111",
    gitCommitSha: "b".repeat(40),
    analyzerVersion: "legacy-analyzer-1",
    scanCompletedAt: "2026-10-01T12:00:00Z",
    ...overrides,
  };
}

export function page<T>(items: T[], overrides: Partial<Page<T>> = {}): Page<T> {
  return { items, totalCount: items.length, offset: 0, limit: 100, truncated: false, ...overrides };
}

export function symbol(overrides: Partial<Symbol> = {}): Symbol {
  return {
    stableId: "java:type:demo.CustomerAction",
    kind: "CLASS",
    simpleName: "CustomerAction",
    qualifiedName: "demo.CustomerAction",
    signature: null,
    resolutionState: "RESOLVED",
    sourcePath: "src/demo/CustomerAction.java",
    startLine: 1,
    startColumn: 1,
    endLine: 60,
    endColumn: 1,
    ...overrides,
  };
}

export function edge(overrides: Partial<Relationship> = {}): Relationship {
  return {
    sourceId: "java:type:demo.CustomerAction",
    targetId: "java:type:demo.CustomerService",
    targetDescription: null,
    type: "CALLS",
    resolutionState: "RESOLVED",
    sourcePath: "src/demo/CustomerAction.java",
    line: 12,
    column: 5,
    evidenceType: "AST",
    ...overrides,
  };
}

export function path(overrides: Partial<TraversalPath> = {}): TraversalPath {
  return {
    nodes: ["java:type:demo.CustomerAction", "java:type:demo.CustomerService"],
    evidence: [edge()],
    resolutionState: "RESOLVED",
    termination: "STEP",
    ...overrides,
  };
}

export function traversal(overrides: Partial<TraversalResult> = {}): TraversalResult {
  return {
    paths: [path()],
    frontiers: [],
    truncated: false,
    truncationReasons: [],
    expandedNodes: 2,
    examinedEdges: 1,
    workLimit: 5000,
    bounds: { depth: 12, limit: 100, fanOut: 50 },
    ...overrides,
  };
}

export function candidate(overrides: Partial<Candidate> = {}): Candidate {
  return {
    stableId: "one:Shared",
    kind: "CLASS",
    simpleName: "Shared",
    qualifiedName: "demo.Shared",
    resolutionState: "RESOLVED",
    ...overrides,
  };
}

export function impact(overrides: Partial<TableImpact> = {}): TableImpact {
  return {
    tableId: "db:table:CUSTOMER",
    componentId: "java:type:demo.CustomerDAO",
    access: "READ",
    direct: true,
    path: {
      nodes: ["java:type:demo.CustomerDAO", "db:table:CUSTOMER"],
      evidence: [edge({ sourceId: "java:type:demo.CustomerDAO", targetId: "db:table:CUSTOMER", type: "READS_TABLE", sourcePath: "src/demo/CustomerDAO.java", line: 40 })],
      resolutionState: "RESOLVED",
      termination: "STEP",
    },
    ...overrides,
  };
}

export function answer(overrides: Partial<Answer> = {}): Answer {
  return {
    freshness: freshness(),
    selection: "SELECTED",
    candidates: [candidate({ stableId: "java:type:demo.CustomerAction" })],
    candidatesTruncated: false,
    direction: "OUTGOING",
    traversal: traversal(),
    tables: [],
    frontierSymbols: [],
    ...overrides,
  };
}

export function entryPoint(overrides: Partial<EntryPoint> = {}): EntryPoint {
  return {
    stableId: "/customer/search",
    path: "/customer/search",
    sourcePath: "web/WEB-INF/struts-config.xml",
    line: 6,
    dispatchParameter: "operation=search",
    ...overrides,
  };
}

export function analysisError(overrides: Partial<AnalysisError> = {}): AnalysisError {
  return {
    relativePath: "src/demo/Broken.java",
    stage: "java-index",
    code: "PARSE_ERROR",
    message: "Source file could not be parsed; other files completed.",
    resolutionState: "UNRESOLVED",
    ...overrides,
  };
}

export function scan(overrides: Partial<Scan> = {}): Scan {
  return {
    id: "11111111-1111-1111-1111-111111111111",
    status: "COMPLETED",
    repositoryRoot: "/fixtures/legacy",
    analyzerVersion: "legacy-analyzer-1",
    gitCommitSha: "b".repeat(40),
    createdAt: "2026-10-01T11:59:00Z",
    startedAt: "2026-10-01T11:59:01Z",
    completedAt: "2026-10-01T12:00:00Z",
    failureMessage: null,
    fileCount: 42,
    errorCount: 3,
    ...overrides,
  };
}

export function scanDetail(overrides: Partial<ScanDetail> = {}): ScanDetail {
  return {
    scan: scan(),
    active: true,
    files: page([]),
    errors: page([analysisError()]),
    ...overrides,
  };
}

/** Stubs global fetch with path-keyed JSON responses. */
export function stubFetch(routes: Record<string, unknown>, statuses: Record<string, number> = {}) {
  const positions = new Map<string, number>();
  return async (input: RequestInfo | URL): Promise<Response> => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
    const path = new URL(url).pathname;
    const headers = { "content-type": "application/json" };
    if (!(path in routes)) {
      return new Response(JSON.stringify({ error: "Not found." }), { status: 404, headers });
    }
    const position = positions.get(path) ?? 0;
    positions.set(path, position + 1);
    const body = routes[path];
    const value = Array.isArray(body) ? body[Math.min(position, body.length - 1)] : body;
    return new Response(JSON.stringify(value), { status: statuses[path] ?? 200, headers });
  };
}
