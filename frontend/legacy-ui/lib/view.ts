/**
 * Presentation helpers.
 *
 * These functions only re-order and label evidence that the backend already
 * decided. They never compute relationships, resolution state, confidence,
 * access kind or directness.
 */
import type { Relationship, TracePath, TraversalPath } from "./api";

export type Chain = {
  nodes: string[];
  evidence: Relationship[];
  resolutionState: string;
  termination: string;
};

export type TraceStep =
  | { kind: "node"; key: string; id: string }
  | { kind: "edge"; key: string; edge: Relationship };

/** Normalizes the two backend chain shapes (traversal paths and entry-point trace paths). */
export function asChain(path: TraversalPath | TracePath): Chain {
  const nodes = "nodes" in path ? path.nodes : path.components;
  return { nodes, evidence: path.evidence, resolutionState: path.resolutionState, termination: path.termination };
}

/**
 * Interleaves nodes and edges in backend order: node, edge, node, edge...
 * A trailing edge with no confirmed target is kept as the final step.
 */
export function traceSteps(chain: Chain): TraceStep[] {
  const steps: TraceStep[] = [];
  chain.nodes.forEach((id, index) => {
    steps.push({ kind: "node", key: `n${index}`, id });
    const edge = chain.evidence[index];
    if (edge) steps.push({ kind: "edge", key: `e${index}`, edge });
  });
  for (let index = chain.nodes.length; index < chain.evidence.length; index += 1) {
    steps.push({ kind: "edge", key: `e${index}`, edge: chain.evidence[index] });
  }
  return steps;
}

const ID_PREFIXES = ["java:type:", "java:method:", "java:field:", "java:package:", "java:annotation:"];

/** Compact display label for a stable ID; the full ID is always shown separately. */
export function nodeLabel(stableId: string): string {
  let label = stableId;
  for (const prefix of ID_PREFIXES) {
    if (label.startsWith(prefix)) {
      label = label.slice(prefix.length);
      break;
    }
  }
  if (label.startsWith("spring:bean:")) {
    const bean = label.slice("spring:bean:".length);
    label = bean.includes("#") ? bean.slice(bean.lastIndexOf("#") + 1) : bean;
  }
  if (label.startsWith("db:table:")) label = label.slice("db:table:".length);
  return label;
}

export function evidenceLocation(edge: Relationship): string {
  return `${edge.sourcePath}:${edge.line}:${edge.column}`;
}

export function shortLocation(edge: Relationship): string {
  return `${edge.sourcePath}:${edge.line}`;
}

/** Renders a backend timestamp without inventing a timezone; null means no active scan. */
export function formatTimestamp(value: string | null | undefined): string {
  if (!value) return "—";
  return value.length > 19 ? `${value.slice(0, 19)}Z` : value;
}

export function formatCount(value: number | null | undefined): string {
  return typeof value === "number" ? String(value) : "—";
}

export const TRUNCATION_HINTS: Record<string, string> = {
  DEPTH_LIMIT: "traversal depth bound reached",
  FAN_OUT_LIMIT: "per-node fan-out bound reached",
  RESULT_LIMIT: "result count bound reached",
  WORK_LIMIT: "graph work budget reached",
  FRONTIER_LIMIT: "frontier bound reached",
};

export function describeTruncation(reasons: string[]): string {
  if (reasons.length === 0) return "Results are incomplete; raise the applicable bounds.";
  return reasons.map((reason) => TRUNCATION_HINTS[reason] ?? reason).join("; ");
}

export function isUnresolvedTarget(edge: Relationship): boolean {
  return edge.targetId === null;
}

export function firstParam(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

/** Falls back to the default when a URL bound is missing or outside the range the backend accepts. */
export function intParam(value: string | string[] | undefined, fallback: number, min: number, max: number): number {
  const raw = firstParam(value);
  const parsed = raw === undefined || raw === "" ? Number.NaN : Number.parseInt(raw, 10);
  return Number.isNaN(parsed) || parsed < min || parsed > max ? fallback : parsed;
}
