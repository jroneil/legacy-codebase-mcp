import { Pagination } from "@/components/Pagination";
import { ErrorNotice, Mono, Panel, StateBadge } from "@/components/ui";
import { listScans, scanDetail } from "@/lib/api";
import { firstParam, intParam } from "@/lib/view";

export const dynamic = "force-dynamic";

export default async function ErrorsPage({
  searchParams,
}: {
  searchParams: Promise<{ [key: string]: string | string[] | undefined }>;
}) {
  const params = await searchParams;
  const requested = firstParam(params.scanId);
  const offset = intParam(params.offset, 0, 0, 1_000_000);

  let scanId = requested;
  let resolveError: string | null = null;
  if (!scanId) {
    const list = await listScans(1, 0);
    if (!list.ok) resolveError = list.error;
    else scanId = list.data.activeScanId ?? undefined;
  }

  if (resolveError) {
    return (
      <div className="flex flex-col gap-4">
        <h1 className="text-xl font-semibold">Analysis errors</h1>
        <ErrorNotice message={resolveError} />
      </div>
    );
  }

  if (!scanId) {
    return (
      <div className="flex flex-col gap-4">
        <h1 className="text-xl font-semibold">Analysis errors</h1>
        <p className="rounded border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900 dark:border-amber-700 dark:bg-amber-950 dark:text-amber-100">
          No active completed scan. Analysis errors are recorded per scan; see <a className="underline" href="/scan">scan status</a>.
        </p>
      </div>
    );
  }

  const detail = await scanDetail(scanId, 100, offset);
  if (!detail.ok) {
    return (
      <div className="flex flex-col gap-4">
        <h1 className="text-xl font-semibold">Analysis errors</h1>
        <ErrorNotice message={detail.error} />
      </div>
    );
  }

  const errors = detail.data.errors;
  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-xl font-semibold">Analysis errors</h1>
      <p className="text-sm">
        Scan <Mono>{detail.data.scan.id}</Mono> · <StateBadge state={detail.data.scan.status} /> · {errors.totalCount} recorded
      </p>
      <Panel title="Localized analysis errors">
        {errors.items.length === 0 ? (
          <p className="text-sm text-zinc-500">No analysis errors were recorded for this scan.</p>
        ) : (
          <table className="w-full text-left text-sm">
            <thead className="text-xs uppercase tracking-wide text-zinc-500">
              <tr>
                <th className="py-1 pr-3">Source file</th>
                <th className="py-1 pr-3">Stage</th>
                <th className="py-1 pr-3">Code</th>
                <th className="py-1 pr-3">State</th>
                <th className="py-1">Message</th>
              </tr>
            </thead>
            <tbody>
              {errors.items.map((error, index) => (
                <tr key={`${error.relativePath}-${error.code}-${index}`} className="border-t border-black/5 align-top dark:border-white/10">
                  <td className="py-2 pr-3">
                    <Mono>{error.relativePath ?? "—"}</Mono>
                  </td>
                  <td className="py-2 pr-3">
                    <Mono>{error.stage}</Mono>
                  </td>
                  <td className="py-2 pr-3">
                    <Mono>{error.code}</Mono>
                  </td>
                  <td className="py-2 pr-3">
                    <StateBadge state={error.resolutionState} />
                  </td>
                  <td className="py-2">{error.message}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <div className="mt-3">
          <Pagination page={errors} basePath="/errors" params={{ scanId }} />
        </div>
      </Panel>
    </div>
  );
}
