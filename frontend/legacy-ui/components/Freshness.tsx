import type { Freshness } from "@/lib/api";
import { formatTimestamp } from "@/lib/view";
import { NoScanNotice } from "./ui";

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex flex-col gap-0.5 sm:flex-row sm:gap-2">
      <dt className="w-40 shrink-0 text-xs uppercase tracking-wide text-zinc-500">{label}</dt>
      <dd className="font-mono text-xs break-all">{value}</dd>
    </div>
  );
}

/** Scan freshness metadata exactly as returned by the backend. */
export function FreshnessPanel({ freshness }: { freshness: Freshness | null }) {
  if (!freshness) return <NoScanNotice />;
  return (
    <div>
      <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-zinc-500">Scan freshness</h2>
      <dl className="flex flex-col gap-1">
        <Row label="scanId" value={freshness.scanId} />
        <Row label="gitCommitSha" value={freshness.gitCommitSha ?? "—"} />
        <Row label="analyzerVersion" value={freshness.analyzerVersion ?? "—"} />
        <Row label="scanCompletedAt" value={formatTimestamp(freshness.scanCompletedAt)} />
      </dl>
    </div>
  );
}
