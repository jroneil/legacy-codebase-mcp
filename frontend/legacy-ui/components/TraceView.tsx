import Link from "next/link";
import { encodeId, type Relationship } from "@/lib/api";
import { describeTruncation, evidenceLocation, nodeLabel, traceSteps, type Chain } from "@/lib/view";
import { EmptyNotice, Mono, StateBadge, TruncatedNotice } from "./ui";

function NodeStep({ id }: { id: string }) {
  return (
    <li className="py-0.5">
      <Link className="underline" href={`/symbols/${encodeId(id)}`} title={id}>
        <Mono>{nodeLabel(id)}</Mono>
      </Link>
    </li>
  );
}

function EdgeStep({ edge }: { edge: Relationship }) {
  const unresolved = edge.targetId === null;
  return (
    <li className="py-0.5 pl-4 text-xs text-zinc-600 dark:text-zinc-300">
      <span className="mr-2">↓</span>
      <Mono>{edge.type}</Mono> · <StateBadge state={edge.resolutionState} />
      <span className="ml-2 text-zinc-500">
        <Mono>{evidenceLocation(edge)}</Mono>
      </span>
      {unresolved ? (
        <span className="ml-2 text-zinc-600 dark:text-zinc-300">→ {edge.targetDescription ?? "unresolved target"}</span>
      ) : null}
    </li>
  );
}

/** Renders each backend path as an ordered chain: node, edge, node, edge, ... */
export function TraceView({
  chains,
  truncated = false,
  truncationReasons = [],
  emptyMessage,
}: {
  chains: Chain[];
  truncated?: boolean;
  truncationReasons?: string[];
  emptyMessage: string;
}) {
  return (
    <div className="flex flex-col gap-3">
      {truncated ? <TruncatedNotice message={describeTruncation(truncationReasons)} /> : null}
      {chains.length === 0 ? (
        <EmptyNotice message={emptyMessage} />
      ) : (
        <ol className="flex flex-col gap-4">
          {chains.map((chain, chainIndex) => (
            <li key={chainIndex} className="rounded border border-black/10 p-3 dark:border-white/15">
              <ol>
                {traceSteps(chain).map((step) =>
                  step.kind === "node" ? <NodeStep key={step.key} id={step.id} /> : <EdgeStep key={step.key} edge={step.edge} />,
                )}
              </ol>
              <p className="mt-2 text-xs text-zinc-500">
                path state <StateBadge state={chain.resolutionState} /> · termination <Mono>{chain.termination}</Mono>
              </p>
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}
