import { FreshnessPanel } from "@/components/Freshness";
import { Pagination } from "@/components/Pagination";
import { SymbolResults } from "@/components/SymbolResults";
import { ErrorNotice, Panel } from "@/components/ui";
import { searchSymbols } from "@/lib/api";
import { firstParam, intParam } from "@/lib/view";

export const dynamic = "force-dynamic";

export default async function SearchPage({
  searchParams,
}: {
  searchParams: Promise<{ [key: string]: string | string[] | undefined }>;
}) {
  const params = await searchParams;
  const query = firstParam(params.q) ?? "";
  const offset = intParam(params.offset, 0, 0, 1_000_000);
  const result = await searchSymbols(query, 100, offset);

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-xl font-semibold">Symbol search</h1>
      <form className="flex flex-wrap items-end gap-2" action="/" method="get">
        <label className="flex flex-col text-xs uppercase tracking-wide text-zinc-500">
          Query
          <input
            className="mt-1 w-80 rounded border border-black/20 px-2 py-1 font-mono text-sm dark:border-white/20 dark:bg-transparent"
            type="search"
            name="q"
            defaultValue={query}
            placeholder="Customer, /customer/search, java:type:demo.CustomerDAO"
          />
        </label>
        <button className="rounded border border-black/20 px-3 py-1 text-sm dark:border-white/20" type="submit">
          Search
        </button>
      </form>

      {!result.ok ? (
        <ErrorNotice message={result.error} />
      ) : (
        <>
          <Panel title="Scan freshness">
            <FreshnessPanel freshness={result.data.freshness} />
          </Panel>
          <Panel title={query === "" ? "All symbols" : `Matches for "${query}"`}>
            <SymbolResults symbols={result.data.candidates.items} />
            <div className="mt-3">
              <Pagination page={result.data.candidates} basePath="/" params={{ q: query }} />
            </div>
          </Panel>
        </>
      )}
    </div>
  );
}
