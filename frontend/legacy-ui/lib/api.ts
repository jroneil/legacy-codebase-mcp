/**
 * Typed, read-only client for the backend REST API.
 *
 * This module only transports and names backend evidence. It never derives
 * relationships, confidence, resolution state, access kind or directness.
 */

export type ApiResult<T> = { ok: true; data: T } | { ok: false; error: string; status?: number };

export type Freshness = {
  scanId: string;
  gitCommitSha: string | null;
  analyzerVersion: string | null;
  scanCompletedAt: string | null;
};

export type Page<T> = {
  items: T[];
  totalCount: number;
  offset: number;
  limit: number;
  truncated: boolean;
};

export type Symbol = {
  stableId: string;
  kind: string;
  simpleName: string;
  qualifiedName: string;
  signature: string | null;
  resolutionState: string;
  sourcePath: string;
  startLine: number;
  startColumn: number;
  endLine: number;
  endColumn: number;
};

export type Relationship = {
  sourceId: string;
  targetId: string | null;
  targetDescription: string | null;
  type: string;
  resolutionState: string;
  sourcePath: string;
  line: number;
  column: number;
  evidenceType: string;
};

export type TraversalPath = {
  nodes: string[];
  evidence: Relationship[];
  resolutionState: string;
  termination: string;
};

export type TraversalResult = {
  paths: TraversalPath[];
  frontiers: TraversalPath[];
  truncated: boolean;
  truncationReasons: string[];
  expandedNodes: number;
  examinedEdges: number;
  workLimit: number;
  bounds: { depth: number; limit: number; fanOut: number };
};

export type Candidate = {
  stableId: string;
  kind: string;
  simpleName: string;
  qualifiedName: string;
  resolutionState: string;
};

export type TableImpact = {
  tableId: string;
  componentId: string;
  access: string;
  direct: boolean;
  path: TraversalPath;
};

export type Answer = {
  freshness: Freshness | null;
  selection: string;
  candidates: Candidate[];
  candidatesTruncated: boolean;
  direction: string;
  traversal: TraversalResult | null;
  tables: TableImpact[];
  frontierSymbols: Candidate[];
};

export type EntryPoint = {
  stableId: string;
  path: string;
  sourcePath: string;
  line: number;
  dispatchParameter: string | null;
};

export type TracePath = {
  components: string[];
  evidence: Relationship[];
  resolutionState: string;
  termination: string;
};

export type EntryTrace = {
  freshness: Freshness | null;
  entryId: string;
  paths: TracePath[];
  truncated: boolean;
};

export type Scan = {
  id: string;
  status: string;
  repositoryRoot: string | null;
  analyzerVersion: string | null;
  gitCommitSha: string | null;
  createdAt: string | null;
  startedAt: string | null;
  completedAt: string | null;
  failureMessage: string | null;
  fileCount: number;
  errorCount: number;
};

export type SourceFile = {
  relativePath: string;
  fileType: string;
  contentHash: string;
  encoding: string;
  sizeBytes: number;
};

export type AnalysisError = {
  relativePath: string | null;
  stage: string;
  code: string;
  message: string;
  resolutionState: string;
};

export type ScanList = { activeScanId: string | null; scans: Page<Scan> };
export type RepositoryItem = { id: string; name: string };
export type RepositoryList = { items: RepositoryItem[]; totalCount: number; truncated: boolean };
export type ScanDetail = { scan: Scan; active: boolean; files: Page<SourceFile>; errors: Page<AnalysisError> };

export const DEFAULT_API_BASE_URL = "http://127.0.0.1:8080";

export function apiBaseUrl(): string {
  return process.env.LEGACY_API_BASE_URL?.trim() || DEFAULT_API_BASE_URL;
}

/** Percent-encodes a stable ID so IDs containing '#', ':' or '/' survive a path segment. */
export function encodeId(stableId: string): string {
  return encodeURIComponent(stableId);
}

/**
 * Dynamic route segments arrive percent-encoded (Next.js does not decode them),
 * so a stable ID containing '/' or '#' must be decoded before use.
 */
export function decodeId(segment: string): string {
  try {
    return decodeURIComponent(segment);
  } catch {
    return segment;
  }
}

async function get<T>(path: string, params: Record<string, string | number | undefined> = {}): Promise<ApiResult<T>> {
  const url = new URL(path, apiBaseUrl());
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== "") url.searchParams.set(key, String(value));
  }
  let response: Response;
  try {
    response = await fetch(url, { cache: "no-store" });
  } catch {
    return { ok: false, error: `Backend unreachable at ${apiBaseUrl()}. Start the backend and retry.` };
  }
  if (!response.ok) {
    return { ok: false, status: response.status, error: await describeFailure(response) };
  }
  try {
    return { ok: true, data: (await response.json()) as T };
  } catch {
    return { ok: false, status: response.status, error: "Backend returned a response that is not JSON." };
  }
}

async function postJson<T>(path: string, body: unknown): Promise<ApiResult<T>> {
  const url = new URL(path, apiBaseUrl());
  let response: Response;
  try {
    response = await fetch(url, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body),
      cache: "no-store",
    });
  } catch {
    return { ok: false, error: "Backend unreachable at " + apiBaseUrl() + ". Start the backend and retry." };
  }
  if (!response.ok) {
    return { ok: false, status: response.status, error: await describeFailure(response) };
  }
  try {
    return { ok: true, data: (await response.json()) as T };
  } catch {
    return { ok: false, status: response.status, error: "Backend returned a response that is not JSON." };
  }
}

async function describeFailure(response: Response): Promise<string> {
  let detail = "";
  try {
    const body = (await response.json()) as { error?: unknown };
    if (typeof body?.error === "string") detail = body.error;
  } catch {
    detail = "";
  }
  const prefix = response.status === 404 ? "Not found" : response.status === 400 ? "Invalid request" : `HTTP ${response.status}`;
  return detail ? `${prefix}: ${detail}` : prefix;
}

export function searchSymbols(query: string, limit = 100, offset = 0) {
  return get<{ freshness: Freshness | null; candidates: Page<Symbol> }>("/api/symbols/search", { q: query, limit, offset });
}

export function symbolDetail(stableId: string, limit = 100, offset = 0) {
  return get<{ freshness: Freshness | null; symbol: Symbol; outgoing: Page<Relationship> }>("/api/symbols/detail", {
    stableId,
    limit,
    offset,
  });
}

export function symbolUsages(stableId: string, limit = 100, offset = 0) {
  return get<{ freshness: Freshness | null; usages: Page<Relationship> }>("/api/symbols/usages", {
    stableId,
    limit,
    offset,
  });
}

export function listEntryPoints(path = "", limit = 100, offset = 0) {
  return get<{ freshness: Freshness | null; candidates: Page<EntryPoint> }>("/api/entry-points", { path, limit, offset });
}

export function entryPointTrace(entryId: string, depth = 8, limit = 100) {
  return get<EntryTrace>("/api/entry-points/trace", { entryId, depth, limit });
}

export function traceComponent(component: string, direction = "OUTGOING", depth = 12, limit = 100, fanOut = 50) {
  return get<Answer>("/api/relationships/trace", { component, direction, depth, limit, fanOut });
}

export function databaseTables(component: string, depth = 12, limit = 100, fanOut = 50) {
  return get<Answer>("/api/relationships/database-tables", { component, depth, limit, fanOut });
}

export function tableUsages(table: string, depth = 12, limit = 100, fanOut = 50) {
  return get<Answer>("/api/relationships/table-usages", { table, depth, limit, fanOut });
}

export function listScans(limit = 20, offset = 0) {
  return get<ScanList>("/api/scans", { limit, offset });
}

export function scanDetail(scanId: string, limit = 100, offset = 0) {
  return get<ScanDetail>(`/api/scans/${encodeURIComponent(scanId)}`, { limit, offset });
}

export function listRepositories() {
  return get<RepositoryList>("/api/repositories");
}

export function createScan(repository: string) {
  return postJson<ScanDetail>("/api/scans", { repository });
}
