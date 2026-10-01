import Link from "next/link";

const LINKS = [
  { href: "/", label: "Search" },
  { href: "/entry-points", label: "Entry points" },
  { href: "/trace", label: "Trace" },
  { href: "/tables", label: "Table impact" },
  { href: "/scan", label: "Scan" },
  { href: "/errors", label: "Errors" },
];

export function Nav() {
  return (
    <nav className="border-b border-black/10 bg-white dark:border-white/15 dark:bg-black">
      <ul className="mx-auto flex max-w-5xl flex-wrap gap-4 px-4 py-3 text-sm">
        {LINKS.map((link) => (
          <li key={link.href}>
            <Link className="hover:underline" href={link.href}>
              {link.label}
            </Link>
          </li>
        ))}
      </ul>
    </nav>
  );
}
