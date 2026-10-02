import { FreshnessPanel } from "@/components/Freshness";
import { Pagination } from "@/components/Pagination";
import { ErrorNotice, Mono, Panel } from "@/components/ui";
import { encodeId, listEntryPoints } from "@/lib/api";
import { firstParam, intParam } from "@/lib/view";

export const dynamic = "force-dynamic";

export default async function EntryPointsPage({
  searchParams,
}: {
  searchParams: Promise<{ [key: string]: string | string[] | undefined }>;
}) {
  const params = await searchParams;
  const path = firstParam(params.path) ?? "";
  const offset = intParam(params.offset, 0, 0, 1_000_000);
  const result = await listEntryPoints(path, 100, offset);

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-xl font-semibold">Entry points</h1>
      <form className="flex flex-wrap items-end gap-2" action="/entry-points" method="get">
        <label className="flex flex-col text-xs uppercase tracking-wide text-zinc-500">
          Exact route path
          <input
            className="mt-1 w-80 rounded border border-black/20 px-2 py-1 font-mono text-sm dark:border-white/20 dark:bg-transparent"
            type="search"
            name="path"
            defaultValue={path}
            placeholder="/customer/search"
          />
        </label>
        <button className="rounded border border-black/20 px-3 py-1 text-sm dark:border-white/20" type="submit">
          Filter
        </button>
      </form>

      {!result.ok ? (
        <ErrorNotice message={result.error} />
      ) : (
        <>
          <Panel title="Scan freshness">
            <FreshnessPanel freshness={result.data.freshness} />
          </Panel>
          <Panel title={`Routes (${result.data.candidates.totalCount})`}>
            {result.data.candidates.items.length === 0 ? (
              <p className="text-sm text-zinc-500">No routes matched.</p>
            ) : (
              <table className="w-full text-left text-sm">
                <thead className="text-xs uppercase tracking-wide text-zinc-500">
                  <tr>
                    <th className="py-1 pr-3">Route</th>
                    <th className="py-1 pr-3">Declared at</th>
                    <th className="py-1 pr-3">Route metadata</th>
                    <th className="py-1">Trace</th>
                  </tr>
                </thead>
                <tbody>
                  {result.data.candidates.items.map((entry) => (
                    <tr key={entry.stableId} className="border-t border-black/5 align-top dark:border-white/10">
                      <td className="py-2 pr-3">
                        <Mono>{entry.path}</Mono>
                      </td>
                      <td className="py-2 pr-3">
                        <Mono>
                          {entry.sourcePath}:{entry.line}
                        </Mono>
                      </td>
                      <td className="py-2 pr-3">
                        <Mono>{entry.dispatchParameter ?? "—"}</Mono>
                      </td>
                      <td className="py-2">
                        <a className="underline" href={`/entry-points/trace?entryId=${encodeId(entry.stableId)}`}>
                          trace
                        </a>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            <div className="mt-3">
              <Pagination page={result.data.candidates} basePath="/entry-points" params={{ path }} />
            </div>
          </Panel>
        </>
      )}
    </div>
  );
}
