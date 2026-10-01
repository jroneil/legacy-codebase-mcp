import { FreshnessPanel } from "@/components/Freshness";
import { TraceView } from "@/components/TraceView";
import { ErrorNotice, Mono, Panel } from "@/components/ui";
import { entryPointTrace } from "@/lib/api";
import { asChain, firstParam, intParam } from "@/lib/view";

export const dynamic = "force-dynamic";

export default async function EntryPointTracePage({
  searchParams,
}: {
  searchParams: Promise<{ [key: string]: string | string[] | undefined }>;
}) {
  const params = await searchParams;
  const entryId = firstParam(params.entryId) ?? "";
  const depth = intParam(params.depth, 8, 0, 8);
  const limit = intParam(params.limit, 100, 1, 100);

  if (entryId === "") {
    return (
      <div className="flex flex-col gap-4">
        <h1 className="text-xl font-semibold">Entry point trace</h1>
        <p className="text-sm text-zinc-500">
          Select a route from <a className="underline" href="/entry-points">entry points</a>.
        </p>
      </div>
    );
  }

  const result = await entryPointTrace(entryId, depth, limit);
  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-xl font-semibold">Entry point trace</h1>
      <p className="text-sm">
        <Mono>{entryId}</Mono>
      </p>
      {!result.ok ? (
        <ErrorNotice message={result.error} />
      ) : (
        <>
          <Panel title="Scan freshness">
            <FreshnessPanel freshness={result.data.freshness} />
          </Panel>
          <Panel title={`Configuration paths (${result.data.paths.length})`}>
            <TraceView
              chains={result.data.paths.map(asChain)}
              truncated={result.data.truncated}
              emptyMessage="No configuration path was indexed for this entry point."
            />
          </Panel>
        </>
      )}
    </div>
  );
}
