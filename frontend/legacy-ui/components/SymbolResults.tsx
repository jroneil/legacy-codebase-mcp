import Link from "next/link";
import { encodeId, type Candidate, type Symbol } from "@/lib/api";
import { EmptyNotice, KindBadge, Mono, StateBadge } from "./ui";

export function SymbolResults({ symbols }: { symbols: Symbol[] }) {
  if (symbols.length === 0) return <EmptyNotice message="No symbols matched this query." />;
  return (
    <table className="w-full text-left text-sm">
      <thead className="text-xs uppercase tracking-wide text-zinc-500">
        <tr>
          <th className="py-1 pr-3">Symbol</th>
          <th className="py-1 pr-3">Kind</th>
          <th className="py-1 pr-3">State</th>
          <th className="py-1">Source</th>
        </tr>
      </thead>
      <tbody>
        {symbols.map((symbol) => (
          <tr key={symbol.stableId} className="border-t border-black/5 align-top dark:border-white/10">
            <td className="py-2 pr-3">
              <Link className="underline" href={`/symbols/${encodeId(symbol.stableId)}`}>
                {symbol.simpleName}
              </Link>
              <div className="text-zinc-500">
                <Mono>{symbol.stableId}</Mono>
              </div>
            </td>
            <td className="py-2 pr-3">
              <KindBadge kind={symbol.kind} />
            </td>
            <td className="py-2 pr-3">
              <StateBadge state={symbol.resolutionState} />
            </td>
            <td className="py-2">
              <Mono>
                {symbol.sourcePath}:{symbol.startLine}
              </Mono>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/**
 * Ambiguous selections are never collapsed: every backend candidate is shown so
 * the developer chooses one explicitly.
 */
export function CandidateList({ candidates, truncated }: { candidates: Candidate[]; truncated: boolean }) {
  if (candidates.length === 0) return <EmptyNotice message="No candidates." />;
  return (
    <div>
      <p className="mb-2 text-sm text-amber-800 dark:text-amber-200">
        {candidates.length} matching symbols. The backend did not select one; choose a candidate to continue.
      </p>
      {truncated ? <p className="mb-2 text-sm text-sky-800 dark:text-sky-200">Candidate list truncated by the backend.</p> : null}
      <ul className="flex flex-col gap-1 text-sm">
        {candidates.map((candidate) => (
          <li key={candidate.stableId} className="flex flex-wrap items-center gap-2">
            <Link className="underline" href={`/symbols/${encodeId(candidate.stableId)}`}>
              <Mono>{candidate.stableId}</Mono>
            </Link>
            <KindBadge kind={candidate.kind} />
            <StateBadge state={candidate.resolutionState} />
          </li>
        ))}
      </ul>
    </div>
  );
}
