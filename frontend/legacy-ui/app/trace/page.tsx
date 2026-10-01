import { CandidateList } from "@/components/SymbolResults";
import { FreshnessPanel } from "@/components/Freshness";
import { TraceView } from "@/components/TraceView";
import { ErrorNotice, Mono, Panel } from "@/components/ui";
import { traceComponent } from "@/lib/api";
import { asChain, describeTruncation, firstParam, intParam } from "@/lib/view";

export const dynamic = "force-dynamic";

export default async function TracePage({
  searchParams,
}: {
  searchParams: Promise<{ [key: string]: string | string[] | undefined }>;
}) {
  const params = await searchParams;
  const component = firstParam(params.component) ?? "";
  const direction = firstParam(params.direction) === "INCOMING" ? "INCOMING" : "OUTGOING";
  const depth = intParam(params.depth, 12, 0, 16);
  const limit = intParam(params.limit, 100, 1, 500);
  const fanOut = intParam(params.fanOut, 50, 1, 200);

  const result = component === "" ? null : await traceComponent(component, direction, depth, limit, fanOut);

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-xl font-semibold">Component trace</h1>
      <form className="flex flex-wrap items-end gap-2" action="/trace" method="get">
        <label className="flex flex-col text-xs uppercase tracking-wide text-zinc-500">
          Component
          <input
            className="mt-1 w-72 rounded border border-black/20 px-2 py-1 font-mono text-sm dark:border-white/20 dark:bg-transparent"
            name="component"
            defaultValue={component}
            placeholder="/customer/search or java:type:demo.CustomerDAO"
          />
        </label>
        <label className="flex flex-col text-xs uppercase tracking-wide text-zinc-500">
          Direction
          <select className="mt-1 rounded border border-black/20 px-2 py-1 text-sm dark:border-white/20 dark:bg-transparent" name="direction" defaultValue={direction}>
            <option value="OUTGOING">OUTGOING</option>
            <option value="INCOMING">INCOMING</option>
          </select>
        </label>
        <label className="flex flex-col text-xs uppercase tracking-wide text-zinc-500">
          depth
          <input className="mt-1 w-16 rounded border border-black/20 px-2 py-1 text-sm dark:border-white/20 dark:bg-transparent" name="depth" type="number" min="0" max="16" defaultValue={depth} />
        </label>
        <label className="flex flex-col text-xs uppercase tracking-wide text-zinc-500">
          limit
          <input className="mt-1 w-20 rounded border border-black/20 px-2 py-1 text-sm dark:border-white/20 dark:bg-transparent" name="limit" type="number" min="1" max="500" defaultValue={limit} />
        </label>
        <label className="flex flex-col text-xs uppercase tracking-wide text-zinc-500">
          fanOut
          <input className="mt-1 w-20 rounded border border-black/20 px-2 py-1 text-sm dark:border-white/20 dark:bg-transparent" name="fanOut" type="number" min="1" max="200" defaultValue={fanOut} />
        </label>
        <button className="rounded border border-black/20 px-3 py-1 text-sm dark:border-white/20" type="submit">
          Trace
        </button>
      </form>

      {result === null ? (
        <p className="text-sm text-zinc-500">Enter a component, route path or stable ID to trace.</p>
      ) : !result.ok ? (
        <ErrorNotice message={result.error} />
      ) : (
        <>
          <Panel title="Scan freshness">
            <FreshnessPanel freshness={result.data.freshness} />
          </Panel>
          <Panel title={`Selection (${result.data.selection})`}>
            {result.data.selection === "AMBIGUOUS" ? (
              <CandidateList candidates={result.data.candidates} truncated={result.data.candidatesTruncated} />
            ) : (
              <p className="text-sm text-zinc-600 dark:text-zinc-300">
                Selected <Mono>{result.data.candidates[0]?.stableId ?? "—"}</Mono> · direction <Mono>{result.data.direction}</Mono>
                {result.data.traversal ? (
                  <>
                    {" "}
                    · bounds depth <Mono>{result.data.traversal.bounds.depth}</Mono>, limit <Mono>{result.data.traversal.bounds.limit}</Mono>, fanOut{" "}
                    <Mono>{result.data.traversal.bounds.fanOut}</Mono>
                  </>
                ) : null}
              </p>
            )}
          </Panel>
          {result.data.traversal ? (
            <>
              <Panel title={`Paths (${result.data.traversal.paths.length})`}>
                <TraceView
                  chains={result.data.traversal.paths.map(asChain)}
                  truncated={result.data.traversal.truncated}
                  truncationReasons={result.data.traversal.truncationReasons}
                  emptyMessage="No path matched from this component."
                />
                {result.data.traversal.truncated ? (
                  <p className="mt-2 text-xs text-zinc-500">
                    Reasons: <Mono>{describeTruncation(result.data.traversal.truncationReasons)}</Mono>
                  </p>
                ) : null}
              </Panel>
              <Panel title={`Frontiers (${result.data.traversal.frontiers.length})`}>
                <TraceView chains={result.data.traversal.frontiers.map(asChain)} emptyMessage="No frontier paths." />
              </Panel>
            </>
          ) : null}
        </>
      )}
    </div>
  );
}
