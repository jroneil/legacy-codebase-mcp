import Link from "next/link";
import { encodeId, type TableImpact } from "@/lib/api";
import { asChain, describeTruncation, evidenceLocation, nodeLabel } from "@/lib/view";
import { EmptyNotice, Mono, StateBadge, TruncatedNotice } from "./ui";

const ACCESS_TONE: Record<string, string> = {
  READ: "border-emerald-400 text-emerald-800 dark:text-emerald-200",
  WRITE: "border-rose-400 text-rose-800 dark:text-rose-200",
  MAPPING: "border-zinc-400 text-zinc-700 dark:text-zinc-300",
};

export function AccessBadge({ access }: { access: string }) {
  return <span className={`rounded border px-1.5 py-0.5 font-mono text-xs ${ACCESS_TONE[access] ?? ACCESS_TONE.MAPPING}`}>{access}</span>;
}

/** Table impact rows: access kind, direct vs transitive and the supporting path. */
export function TableImpactList({
  impacts,
  truncated = false,
  truncationReasons = [],
}: {
  impacts: TableImpact[];
  truncated?: boolean;
  truncationReasons?: string[];
}) {
  if (impacts.length === 0) return <EmptyNotice message="No database table impact was indexed for this query." />;
  return (
    <div className="flex flex-col gap-3">
      {truncated ? <TruncatedNotice message={describeTruncation(truncationReasons)} /> : null}
      <ul className="flex flex-col gap-2">
        {impacts.map((impact, index) => {
          const chain = asChain(impact.path);
          return (
            <li key={`${impact.tableId}-${impact.access}-${index}`} className="rounded border border-black/10 p-3 text-sm dark:border-white/15">
              <div className="flex flex-wrap items-center gap-2">
                <Link className="underline" href={`/tables/${encodeId(impact.tableId)}`}>
                  <Mono>{nodeLabel(impact.tableId)}</Mono>
                </Link>
                <AccessBadge access={impact.access} />
                <span className="text-xs text-zinc-600 dark:text-zinc-300">{impact.direct ? "direct" : "transitive"}</span>
                <span className="text-xs text-zinc-500">path state</span>
                <StateBadge state={chain.resolutionState} />
              </div>
              <details className="mt-2">
                <summary className="cursor-pointer text-xs text-zinc-500">supporting evidence path ({chain.evidence.length} edges)</summary>
                <ol className="mt-2 flex flex-col gap-1 text-xs">
                  {chain.nodes.map((id, nodeIndex) => (
                    <li key={`n${nodeIndex}`} className="pl-2">
                      <Mono>{nodeLabel(id)}</Mono>
                      {chain.evidence[nodeIndex] ? (
                        <span className="ml-2 text-zinc-500">
                          ↓ <Mono>{chain.evidence[nodeIndex].type}</Mono> · {chain.evidence[nodeIndex].resolutionState} ·{" "}
                          <Mono>{evidenceLocation(chain.evidence[nodeIndex])}</Mono>
                        </span>
                      ) : null}
                    </li>
                  ))}
                </ol>
              </details>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
