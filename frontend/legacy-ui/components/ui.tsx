import type { ReactNode } from "react";

export function Panel({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="rounded border border-black/10 p-4 dark:border-white/15">
      <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-zinc-500">{title}</h2>
      {children}
    </section>
  );
}

export function ErrorNotice({ message }: { message: string }) {
  return (
    <p role="alert" className="rounded border border-red-300 bg-red-50 p-3 text-sm text-red-900 dark:border-red-800 dark:bg-red-950 dark:text-red-100">
      {message}
    </p>
  );
}

export function EmptyNotice({ message }: { message: string }) {
  return <p className="text-sm text-zinc-500">{message}</p>;
}

export function NoScanNotice() {
  return (
    <p className="rounded border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900 dark:border-amber-700 dark:bg-amber-950 dark:text-amber-100">
      No active completed scan. Trigger a scan (POST /api/scans) and reload; see <a className="underline" href="/scan">scan status</a>.
    </p>
  );
}

export function TruncatedNotice({ message }: { message: string }) {
  return (
    <p role="status" className="rounded border border-sky-300 bg-sky-50 p-3 text-sm text-sky-900 dark:border-sky-800 dark:bg-sky-950 dark:text-sky-100">
      Truncated: results are incomplete. {message}
    </p>
  );
}

export function StateBadge({ state }: { state: string }) {
  const tone =
    state === "RESOLVED"
      ? "border-emerald-400 text-emerald-800 dark:text-emerald-200"
      : state === "INFERRED"
        ? "border-amber-400 text-amber-800 dark:text-amber-200"
        : "border-zinc-400 text-zinc-700 dark:text-zinc-300";
  return <span className={`rounded border px-1.5 py-0.5 font-mono text-xs ${tone}`}>{state}</span>;
}

export function KindBadge({ kind }: { kind: string }) {
  return <span className="rounded bg-black/[.06] px-1.5 py-0.5 font-mono text-xs dark:bg-white/[.1]">{kind}</span>;
}

export function Mono({ children }: { children: ReactNode }) {
  return <span className="font-mono text-xs break-all">{children}</span>;
}
