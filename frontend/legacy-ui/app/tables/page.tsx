import { FreshnessPanel } from "@/components/Freshness";
import { TableImpactList } from "@/components/TableImpactList";
import { ErrorNotice, Mono, Panel } from "@/components/ui";
import { databaseTables } from "@/lib/api";
import { describeTruncation, firstParam, intParam } from "@/lib/view";

export const dynamic = "force-dynamic";

export default async function TableImpactPage({
  searchParams,
}: {
  searchParams: Promise<{ [key: string]: string | string[] | undefined }>;
}) {
  const params = await searchParams;
  const component = firstParam(params.component) ?? "";
  const depth = intParam(params.depth, 12, 0, 16);
  const limit = intParam(params.limit, 100, 1, 500);
  const fanOut = intParam(params.fanOut, 50, 1, 200);

  const result = component === "" ? null : await databaseTables(component, depth, limit, fanOut);

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-xl font-semibold">Database table impact</h1>
      <p className="text-sm text-zinc-500">
        Tables reachable from a component, with read/write/mapping access and direct vs transitive paths. A table is never shown as impact
        without its supporting evidence path.
      </p>
      <form className="flex flex-wrap items-end gap-2" action="/tables" method="get">
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
          depth
          <input className="mt-1 w-16 rounded border border-black/20 px-2 py-1 text-sm dark:border-white/20 dark:bg-transparent" name="depth" type="number" min="0" max="16" defaultValue={depth} />
        </label>
        <label className="flex flex-col text-xs uppercase tracking-wide text-zinc-500">
          limit
          <input className="mt-1 w-20 rounded border border-black/20 px-2 py-1 text-sm dark:border-white/20 dark:bg-transparent" name="limit" type="number" min="1" max="500" defaultValue={limit} />
        </label>
        <button className="rounded border border-black/20 px-3 py-1 text-sm dark:border-white/20" type="submit">
          Inspect
        </button>
      </form>

      {result === null ? (
        <p className="text-sm text-zinc-500">Enter a component to inspect table impact.</p>
      ) : !result.ok ? (
        <ErrorNotice message={result.error} />
      ) : (
        <>
          <Panel title="Scan freshness">
            <FreshnessPanel freshness={result.data.freshness} />
          </Panel>
          <Panel title={`Selection (${result.data.selection})`}>
            {result.data.selection === "AMBIGUOUS" ? (
              <p className="text-sm text-amber-800 dark:text-amber-200">
                {result.data.candidates.length} matching components; use an exact stable ID from{" "}
                <a className="underline" href={`/?q=${encodeURIComponent(component)}`}>
                  search
                </a>
                .
              </p>
            ) : (
              <p className="text-sm text-zinc-600 dark:text-zinc-300">
                Selected <Mono>{result.data.candidates[0]?.stableId ?? "—"}</Mono>
              </p>
            )}
          </Panel>
          <Panel title={`Table impact (${result.data.tables.length})`}>
            <TableImpactList
              impacts={result.data.tables}
              truncated={result.data.traversal?.truncated ?? false}
              truncationReasons={result.data.traversal?.truncationReasons ?? []}
            />
            {result.data.traversal?.truncated ? (
              <p className="mt-2 text-xs text-zinc-500">Reasons: <Mono>{describeTruncation(result.data.traversal.truncationReasons)}</Mono></p>
            ) : null}
          </Panel>
        </>
      )}
    </div>
  );
}
