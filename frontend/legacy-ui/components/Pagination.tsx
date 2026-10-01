import Link from "next/link";
import type { Page } from "@/lib/api";

export function buildHref(basePath: string, params: Record<string, string | number | undefined>, offset: number): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== "") search.set(key, String(value));
  }
  search.set("offset", String(Math.max(0, offset)));
  return `${basePath}?${search.toString()}`;
}

/** Bounded-result summary plus previous/next links; always states when results are incomplete. */
export function Pagination({
  page,
  basePath,
  params,
}: {
  page: Page<unknown>;
  basePath: string;
  params: Record<string, string | number | undefined>;
}) {
  const returned = page.items.length;
  const start = returned === 0 ? 0 : page.offset + 1;
  const end = page.offset + returned;
  const previous = page.offset > 0 ? Math.max(0, page.offset - page.limit) : null;
  const next = page.truncated ? page.offset + returned : null;
  return (
    <div className="flex flex-wrap items-center gap-3 text-xs text-zinc-600 dark:text-zinc-300">
      <span data-testid="page-summary">
        Showing {start}–{end} of {page.totalCount}
      </span>
      {page.truncated ? <span className="text-sky-800 dark:text-sky-200">Truncated: more results exist.</span> : null}
      {previous !== null ? (
        <Link className="underline" href={buildHref(basePath, params, previous)}>
          Previous
        </Link>
      ) : null}
      {next !== null ? (
        <Link className="underline" href={buildHref(basePath, params, next)}>
          Next
        </Link>
      ) : null}
    </div>
  );
}
