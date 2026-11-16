import { useAdminText } from "../../../i18n/use-admin-text.ts";

export interface CatalogTab {
  readonly id: string;
  /** Already translated. */
  readonly label: string;
  readonly href: string;
  readonly current: boolean;
}

export interface CatalogTabsProps {
  readonly tabs: readonly CatalogTab[];
  /** Called on a plain click, which the caller turns into a navigation of the router. */
  readonly onNavigate: (href: string) => void;
}

/**
 * The sub navigation of the catalog: a landmark with one link per view, the current one marked with
 * `aria-current` and with a heavier weight and a bar, so it is never told by color alone. Links keep their `href`,
 * so a modified click (new tab) still works.
 */
export function CatalogTabs({ tabs, onNavigate }: CatalogTabsProps) {
  const { t } = useAdminText();

  return (
    <nav aria-label={t("catalog.tabsLabel")} className="border-b border-border-subtle">
      <ul className="flex flex-wrap gap-1">
        {tabs.map((tab) => (
          <li key={tab.id}>
            <a
              href={tab.href}
              aria-current={tab.current ? "page" : undefined}
              onClick={(event) => {
                if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
                event.preventDefault();
                onNavigate(tab.href);
              }}
              className={[
                "-mb-px block border-b-4 px-3 py-2 text-sm text-text-primary hover:bg-bg-canvas",
                "focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-border-focus",
                tab.current ? "border-border-focus font-semibold" : "border-transparent",
              ].join(" ")}
            >
              {tab.label}
            </a>
          </li>
        ))}
      </ul>
    </nav>
  );
}
