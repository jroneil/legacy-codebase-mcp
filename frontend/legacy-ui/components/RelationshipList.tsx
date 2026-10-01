import Link from "next/link";
import { encodeId, type Relationship } from "@/lib/api";
import { evidenceLocation, nodeLabel, shortLocation } from "@/lib/view";
import { EmptyNotice, Mono, StateBadge } from "./ui";

function NodeLink({ id }: { id: string }) {
  return (
    <Link className="underline" href={`/symbols/${encodeId(id)}`} title={id}>
      <Mono>{nodeLabel(id)}</Mono>
    </Link>
  );
}

/**
 * One row per relationship exactly as indexed: type, resolution state, the
 * target (or its unresolved description) and the evidence location.
 */
export function RelationshipList({
  relationships,
  emptyMessage,
}: {
  relationships: Relationship[];
  emptyMessage: string;
}) {
  if (relationships.length === 0) return <EmptyNotice message={emptyMessage} />;
  return (
    <ol className="flex flex-col divide-y divide-black/5 dark:divide-white/10">
      {relationships.map((edge, index) => (
        <li key={`${edge.sourceId}-${edge.type}-${edge.sourcePath}-${edge.line}-${index}`} className="flex flex-col gap-1 py-2 text-sm">
          <div className="flex flex-wrap items-center gap-2">
            <Mono>{edge.type}</Mono>
            <StateBadge state={edge.resolutionState} />
            <span className="text-zinc-400">→</span>
            {edge.targetId ? (
              <NodeLink id={edge.targetId} />
            ) : (
              <span className="text-zinc-600 dark:text-zinc-300" title="Unresolved target description from the backend">
                {edge.targetDescription ?? "unresolved target"}
              </span>
            )}
          </div>
          <div className="text-xs text-zinc-500" title={`evidenceType=${edge.evidenceType}`}>
            evidence <Mono>{shortLocation(edge)}</Mono> · {evidenceLocation(edge)}
          </div>
        </li>
      ))}
    </ol>
  );
}
