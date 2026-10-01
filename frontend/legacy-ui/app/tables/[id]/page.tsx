import Link from "next/link";
import { FreshnessPanel } from "@/components/Freshness";
import { TableImpactList } from "@/components/TableImpactList";
import { ErrorNotice, Mono, Panel } from "@/components/ui";
import { decodeId, encodeId, tableUsages } from "@/lib/api";
import { describeTruncation, intParam } from "@/lib/view";

export const dynamic = "force-dynamic";

export default async function TablePage({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  searchParams: Promise<{ [key: string]: string | string[] | undefined }>;
}) {
  const { id: segment } = await params;
  const id = decodeId(segment);
  const query = await searchParams;
  const depth = intParam(query.depth, 12, 0, 16);
  const limit = intParam(query.limit, 100, 1, 500);
  const fanOut = intParam(query.fanOut, 50, 1, 200);
  const result = await tableUsages(id, depth, limit, fanOut);

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-xl font-semibold">Table usages</h1>
      <p className="text-sm">
        <Mono>{id}</Mono>
      </p>
      {!result.ok ? (
        <ErrorNotice message={result.error} />
      ) : (
        <>
          <Panel title="Scan freshness">
            <FreshnessPanel freshness={result.data.freshness} />
          </Panel>
          <Panel title={`Components (${result.data.tables.length})`}>
            {result.data.tables.length === 0 ? (
              <p className="text-sm text-zinc-500">No component reads, writes or maps this table in the active scan.</p>
            ) : (
              <ul className="mb-3 flex flex-col gap-1 text-sm">
                {result.data.tables.map((impact, index) => (
                  <li key={`${impact.componentId}-${index}`} className="flex flex-wrap items-center gap-2">
                    <Link className="underline" href={`/trace?component=${encodeId(impact.componentId)}`}>
                      <Mono>{impact.componentId}</Mono>
                    </Link>
                    <span className="text-xs text-zinc-500">{impact.access}</span>
                  </li>
                ))}
              </ul>
            )}
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
