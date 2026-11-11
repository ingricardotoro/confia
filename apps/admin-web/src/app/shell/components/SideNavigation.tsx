import { useAdminText } from "../../../i18n/use-admin-text.ts";

export interface SideNavigationItem {
  readonly id: string;
  /** Already translated. */
  readonly label: string;
  readonly href: string;
  readonly current: boolean;
}

export interface SideNavigationProps {
  readonly items: readonly SideNavigationItem[];
  /** Called on a plain click, which the caller turns into a navigation of the router. */
  readonly onNavigate: (href: string) => void;
}

/**
 * The main navigation: a landmark with one link per entry, the current page marked with
 * `aria-current` and with a heavier weight and a bar, so it is never told by color alone. Links keep
 * their `href`, so a modified click (new tab) and a no-script visit still work.
 */
export function SideNavigation({ items, onNavigate }: SideNavigationProps) {
  const { t } = useAdminText();

  return (
    <nav aria-label={t("shell.navigationLabel")} className="w-56 shrink-0 border-r border-border-subtle bg-bg-surface p-3">
      <ul className="flex flex-col gap-1">
        {items.map((item) => (
          <li key={item.id}>
            <a
              href={item.href}
              aria-current={item.current ? "page" : undefined}
              onClick={(event) => {
                // A modified click keeps its browser meaning (new tab, new window).
                if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
                event.preventDefault();
                onNavigate(item.href);
              }}
              className={[
                "block rounded-md border-l-4 px-3 py-2 text-sm text-text-primary hover:bg-bg-canvas",
                "focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-border-focus",
                item.current ? "border-border-focus bg-bg-canvas font-semibold" : "border-transparent",
              ].join(" ")}
            >
              {item.label}
            </a>
          </li>
        ))}
      </ul>
    </nav>
  );
}
