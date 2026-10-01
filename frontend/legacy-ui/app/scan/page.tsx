import Link from "next/link";
import { FreshnessPanel } from "@/components/Freshness";
import { ErrorNotice, Mono, Panel, StateBadge } from "@/components/ui";
import { encodeId, listScans, scanDetail, type Freshness } from "@/lib/api";
import { formatCount, formatTimestamp } from "@/lib/view";

export const dynamic = "force-dynamic";

function freshnessOf(scan: { id: string; analyzerVersion: string | null; gitCommitSha: string | null; completedAt: string | null }): Freshness {
  return {
    scanId: scan.id,
    gitCommitSha: scan.gitCommitSha,
    analyzerVersion: scan.analyzerVersion,
    scanCompletedAt: scan.completedAt,
  };
}

export default async function ScanPage() {
  const list = await listScans(20, 0);

  if (!list.ok) {
    return (
      <div className="flex flex-col gap-4">
        <h1 className="text-xl font-semibold">Scan status</h1>
        <ErrorNotice message={list.error} />
      </div>
    );
  }

  const activeId = list.data.activeScanId;
  const active = activeId ? await scanDetail(activeId, 1, 0) : null;

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-xl font-semibold">Scan status</h1>
      {active && active.ok ? (
        <>
          <Panel title="Active completed scan">
            <FreshnessPanel freshness={freshnessOf(active.data.scan)} />
            <dl className="mt-3 flex flex-col gap-1 text-sm">
              <div className="flex gap-2">
                <dt className="w-40 text-xs uppercase tracking-wide text-zinc-500">status</dt>
                <dd>
                  <StateBadge state={active.data.scan.status} />
                </dd>
              </div>
              <div className="flex gap-2">
                <dt className="w-40 text-xs uppercase tracking-wide text-zinc-500">fileCount</dt>
                <dd>
                  <Mono>{formatCount(active.data.scan.fileCount)}</Mono>
                </dd>
              </div>
              <div className="flex gap-2">
                <dt className="w-40 text-xs uppercase tracking-wide text-zinc-500">errorCount</dt>
                <dd>
                  <Mono>{formatCount(active.data.scan.errorCount)}</Mono> ·{" "}
                  <Link className="underline" href={`/errors?scanId=${encodeId(active.data.scan.id)}`}>
                    inspect errors
                  </Link>
                </dd>
              </div>
              <div className="flex gap-2">
                <dt className="w-40 text-xs uppercase tracking-wide text-zinc-500">repositoryRoot</dt>
                <dd>
                  <Mono>{active.data.scan.repositoryRoot ?? "—"}</Mono>
                </dd>
              </div>
              <div className="flex gap-2">
                <dt className="w-40 text-xs uppercase tracking-wide text-zinc-500">completedAt</dt>
                <dd>
                  <Mono>{formatTimestamp(active.data.scan.completedAt)}</Mono>
                </dd>
              </div>
            </dl>
          </Panel>
        </>
      ) : (
        <Panel title="Active completed scan">
          <FreshnessPanel freshness={null} />
        </Panel>
      )}

      <Panel title={`Scans (${list.data.scans.totalCount})`}>
        {list.data.scans.items.length === 0 ? (
          <p className="text-sm text-zinc-500">No scans have been recorded.</p>
        ) : (
          <table className="w-full text-left text-sm">
            <thead className="text-xs uppercase tracking-wide text-zinc-500">
              <tr>
                <th className="py-1 pr-3">Scan</th>
                <th className="py-1 pr-3">Status</th>
                <th className="py-1 pr-3">Git SHA</th>
                <th className="py-1 pr-3">Analyzer</th>
                <th className="py-1 pr-3">Completed</th>
                <th className="py-1 pr-3">Files</th>
                <th className="py-1">Errors</th>
              </tr>
            </thead>
            <tbody>
              {list.data.scans.items.map((scan) => (
                <tr key={scan.id} className="border-t border-black/5 align-top dark:border-white/10">
                  <td className="py-2 pr-3">
                    <Mono>{scan.id}</Mono>
                    {scan.id === activeId ? <span className="ml-2 text-xs text-emerald-700 dark:text-emerald-300">active</span> : null}
                  </td>
                  <td className="py-2 pr-3">
                    <StateBadge state={scan.status} />
                  </td>
                  <td className="py-2 pr-3">
                    <Mono>{scan.gitCommitSha ? scan.gitCommitSha.slice(0, 12) : "—"}</Mono>
                  </td>
                  <td className="py-2 pr-3">
                    <Mono>{scan.analyzerVersion ?? "—"}</Mono>
                  </td>
                  <td className="py-2 pr-3">
                    <Mono>{formatTimestamp(scan.completedAt)}</Mono>
                  </td>
                  <td className="py-2 pr-3">
                    <Mono>{formatCount(scan.fileCount)}</Mono>
                  </td>
                  <td className="py-2">
                    <Link className="underline" href={`/errors?scanId=${encodeId(scan.id)}`}>
                      {formatCount(scan.errorCount)}
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Panel>
    </div>
  );
}
