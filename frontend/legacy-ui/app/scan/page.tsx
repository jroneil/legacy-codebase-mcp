import Link from "next/link";
import { startScan } from "./actions";
import { FreshnessPanel } from "@/components/Freshness";
import { ErrorNotice, Mono, Panel, StateBadge, TruncatedNotice } from "@/components/ui";
import { encodeId, listRepositories, listScans, scanDetail, type Freshness } from "@/lib/api";
import { formatCount, formatTimestamp } from "@/lib/view";

export const dynamic = "force-dynamic";

type SearchParams = Record<string, string | string[] | undefined>;

type Props = {
  searchParams?: Promise<SearchParams>;
};

function valueOf(value: string | string[] | undefined): string {
  return Array.isArray(value) ? (value[0] ?? "") : (value ?? "");
}

function freshnessOf(scan: { id: string; analyzerVersion: string | null; gitCommitSha: string | null; completedAt: string | null }): Freshness {
  return {
    scanId: scan.id,
    gitCommitSha: scan.gitCommitSha,
    analyzerVersion: scan.analyzerVersion,
    scanCompletedAt: scan.completedAt,
  };
}

export default async function ScanPage({ searchParams = Promise.resolve({}) }: Props = {}) {
  const [params, repositories, list] = await Promise.all([searchParams, listRepositories(), listScans(20, 0)]);
  const requestedRepository = valueOf(params.repository);
  const scanId = valueOf(params.scanId);
  const status = valueOf(params.status);
  const actionError = valueOf(params.error);
  const activeId = list.ok ? list.data.activeScanId : null;
  const active = activeId ? await scanDetail(activeId, 1, 0) : null;
  const selectedRepository =
    repositories.ok && repositories.data.items.some((repository) => repository.id === requestedRepository)
      ? requestedRepository
      : repositories.ok
        ? (repositories.data.items[0]?.id ?? "")
        : "";

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-xl font-semibold">Scan status</h1>

      <Panel title="Start a repository scan">
        {repositories.ok ? (
          repositories.data.items.length === 0 ? (
            <p className="text-sm text-zinc-500">
              No repositories are available. Add a visible directory under the configured repository base and reload this page.
            </p>
          ) : (
            <>
              <form action={startScan} className="flex flex-wrap items-end gap-3">
                <label className="flex min-w-64 flex-1 flex-col gap-1 text-sm">
                  <span className="text-xs font-medium uppercase tracking-wide text-zinc-500">Repository</span>
                  <select
                    name="repository"
                    defaultValue={selectedRepository}
                    className="rounded border border-black/15 bg-transparent px-3 py-2 dark:border-white/20"
                  >
                    {repositories.data.items.map((repository) => (
                      <option key={repository.id} value={repository.id}>
                        {repository.name}
                      </option>
                    ))}
                  </select>
                </label>
                <button
                  type="submit"
                  className="rounded bg-zinc-900 px-4 py-2 text-sm font-medium text-white dark:bg-zinc-100 dark:text-zinc-900"
                >
                  Scan
                </button>
              </form>
              {repositories.data.truncated ? (
                <div className="mt-3">
                  <TruncatedNotice message={`Showing ${repositories.data.items.length} of ${repositories.data.totalCount} repositories.`} />
                </div>
              ) : null}
            </>
          )
        ) : (
          <ErrorNotice message={repositories.error} />
        )}
      </Panel>

      {actionError ? <ErrorNotice message={actionError} /> : null}
      {scanId && status === "COMPLETED" ? (
        <p role="status" className="rounded border border-emerald-300 bg-emerald-50 p-3 text-sm text-emerald-900 dark:border-emerald-800 dark:bg-emerald-950 dark:text-emerald-100">
          Scan <Mono>{scanId}</Mono> completed and is now active.
        </p>
      ) : null}
      {scanId && status === "FAILED" ? (
        <ErrorNotice message={`Scan ${scanId} failed. The previous completed scan remains active.`} />
      ) : null}

      {list.ok ? (
        active && active.ok ? (
          <Panel title="Active completed scan">
            <FreshnessPanel freshness={freshnessOf(active.data.scan)} />
            <dl className="mt-3 flex flex-col gap-1 text-sm">
              <div className="flex gap-2">
                <dt className="w-40 text-xs uppercase tracking-wide text-zinc-500">status</dt>
                <dd><StateBadge state={active.data.scan.status} /></dd>
              </div>
              <div className="flex gap-2">
                <dt className="w-40 text-xs uppercase tracking-wide text-zinc-500">fileCount</dt>
                <dd><Mono>{formatCount(active.data.scan.fileCount)}</Mono></dd>
              </div>
              <div className="flex gap-2">
                <dt className="w-40 text-xs uppercase tracking-wide text-zinc-500">errorCount</dt>
                <dd>
                  <Mono>{formatCount(active.data.scan.errorCount)}</Mono> ·{" "}
                  <Link className="underline" href={`/errors?scanId=${encodeId(active.data.scan.id)}`}>inspect errors</Link>
                </dd>
              </div>
              <div className="flex gap-2">
                <dt className="w-40 text-xs uppercase tracking-wide text-zinc-500">repositoryRoot</dt>
                <dd><Mono>{active.data.scan.repositoryRoot ?? "—"}</Mono></dd>
              </div>
              <div className="flex gap-2">
                <dt className="w-40 text-xs uppercase tracking-wide text-zinc-500">completedAt</dt>
                <dd><Mono>{formatTimestamp(active.data.scan.completedAt)}</Mono></dd>
              </div>
            </dl>
          </Panel>
        ) : active && !active.ok ? (
          <Panel title="Active completed scan"><ErrorNotice message={active.error} /></Panel>
        ) : (
          <Panel title="Active completed scan"><FreshnessPanel freshness={null} /></Panel>
        )
      ) : (
        <ErrorNotice message={list.error} />
      )}

      {list.ok ? (
        <Panel title={`Scans (${list.data.scans.totalCount})`}>
          {list.data.scans.items.length === 0 ? (
            <p className="text-sm text-zinc-500">No scans have been recorded.</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead className="text-xs uppercase tracking-wide text-zinc-500">
                  <tr>
                    <th className="py-1 pr-3">Scan</th>
                    <th className="py-1 pr-3">Repository</th>
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
                      <td className="py-2 pr-3"><Mono>{scan.repositoryRoot ?? "—"}</Mono></td>
                      <td className="py-2 pr-3"><StateBadge state={scan.status} /></td>
                      <td className="py-2 pr-3"><Mono>{scan.gitCommitSha ? scan.gitCommitSha.slice(0, 12) : "—"}</Mono></td>
                      <td className="py-2 pr-3"><Mono>{scan.analyzerVersion ?? "—"}</Mono></td>
                      <td className="py-2 pr-3"><Mono>{formatTimestamp(scan.completedAt)}</Mono></td>
                      <td className="py-2 pr-3"><Mono>{formatCount(scan.fileCount)}</Mono></td>
                      <td className="py-2">
                        <Link className="underline" href={`/errors?scanId=${encodeId(scan.id)}`}>{formatCount(scan.errorCount)}</Link>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Panel>
      ) : null}
    </div>
  );
}
