import { FreshnessPanel } from "@/components/Freshness";
import { RelationshipList } from "@/components/RelationshipList";
import { ErrorNotice, KindBadge, Mono, Panel, StateBadge } from "@/components/ui";
import { decodeId, symbolDetail, symbolUsages, type Relationship, type Symbol } from "@/lib/api";
import { intParam } from "@/lib/view";

export const dynamic = "force-dynamic";

function Identity({ symbol }: { symbol: Symbol }) {
  return (
    <dl className="flex flex-col gap-1 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <dt className="text-xs uppercase tracking-wide text-zinc-500">Stable ID</dt>
        <dd>
          <Mono>{symbol.stableId}</Mono>
        </dd>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <dt className="text-xs uppercase tracking-wide text-zinc-500">Kind</dt>
        <dd>
          <KindBadge kind={symbol.kind} />
        </dd>
        <dt className="ml-2 text-xs uppercase tracking-wide text-zinc-500">State</dt>
        <dd>
          <StateBadge state={symbol.resolutionState} />
        </dd>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <dt className="text-xs uppercase tracking-wide text-zinc-500">Qualified name</dt>
        <dd>
          <Mono>{symbol.qualifiedName}</Mono>
        </dd>
      </div>
      {symbol.signature ? (
        <div className="flex flex-wrap items-center gap-2">
          <dt className="text-xs uppercase tracking-wide text-zinc-500">Signature</dt>
          <dd>
            <Mono>{symbol.signature}</Mono>
          </dd>
        </div>
      ) : null}
      <div className="flex flex-wrap items-center gap-2">
        <dt className="text-xs uppercase tracking-wide text-zinc-500">Source</dt>
        <dd title={`columns ${symbol.startColumn}..${symbol.endColumn}`}>
          <Mono>
            {symbol.sourcePath}:{symbol.startLine}-{symbol.endLine}
          </Mono>
        </dd>
      </div>
    </dl>
  );
}

export default async function SymbolPage({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  searchParams: Promise<{ [key: string]: string | string[] | undefined }>;
}) {
  const { id: segment } = await params;
  const id = decodeId(segment);
  const query = await searchParams;
  const limit = intParam(query.limit, 100, 1, 200);
  const [detail, usages] = await Promise.all([symbolDetail(id, limit), symbolUsages(id, limit)]);

  if (!detail.ok) {
    return (
      <div className="flex flex-col gap-4">
        <h1 className="text-xl font-semibold">Symbol</h1>
        <ErrorNotice message={detail.error} />
      </div>
    );
  }

  const incoming: Relationship[] = usages.ok ? usages.data.usages.items : [];

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-xl font-semibold">{detail.data.symbol.simpleName}</h1>
      <Panel title="Symbol">
        <Identity symbol={detail.data.symbol} />
      </Panel>
      <Panel title="Scan freshness">
        <FreshnessPanel freshness={detail.data.freshness} />
      </Panel>
      <Panel title={`Outgoing relationships (${detail.data.outgoing.totalCount})`}>
        <RelationshipList relationships={detail.data.outgoing.items} emptyMessage="No outgoing relationships indexed." />
        {detail.data.outgoing.truncated ? <p className="mt-2 text-xs text-sky-800 dark:text-sky-200">Truncated: more outgoing relationships exist.</p> : null}
      </Panel>
      <Panel title={`Incoming relationships / usages (${usages.ok ? usages.data.usages.totalCount : 0})`}>
        {usages.ok ? (
          <>
            <RelationshipList relationships={incoming} emptyMessage="No incoming relationships indexed." />
            {usages.data.usages.truncated ? <p className="mt-2 text-xs text-sky-800 dark:text-sky-200">Truncated: more usages exist.</p> : null}
          </>
        ) : (
          <ErrorNotice message={usages.error} />
        )}
      </Panel>
    </div>
  );
}
