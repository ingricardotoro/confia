import { ChevronDown, PanelLeftClose, PanelLeftOpen, X } from "lucide-react";
import { useId } from "react";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import confiaLogo from "../../../shared/brand/confia-logo.png";

export interface SideNavigationItem {
  readonly id: string;
  /** Already translated. */
  readonly label: string;
  readonly href: string;
  readonly current: boolean;
}

/** A section of the navigation: a labelled list of entries. */
export interface SideNavigationGroup {
  readonly id: string;
  /** Already translated. The visible name of the section and the accessible name of its list. */
  readonly label: string;
  readonly items: readonly SideNavigationItem[];
}

export interface SideNavigationProps {
  /** The entries listed first, with no section label (Home). */
  readonly items: readonly SideNavigationItem[];
  /** The labelled sections, in the order they are listed. */
  readonly groups?: readonly SideNavigationGroup[];
  /** Called on a plain click, which the caller turns into a navigation of the router. */
  readonly onNavigate: (href: string) => void;
  /** The ids of the sections that are open. The caller owns this state (and where it is kept). */
  readonly expandedGroupIds?: ReadonlySet<string>;
  /** Called with the id of the section whose header was activated. */
  readonly onToggleGroup?: (groupId: string) => void;
  /** From lg up the navigation is a slim rail: the emblem of the logo and the toggle. The caller owns where the choice is kept. */
  readonly collapsed?: boolean;
  /** Called by the toggle at the foot of the navigation (from lg up). */
  readonly onToggleCollapsed?: () => void;
  /** Called by the close control of the small screen panel. */
  readonly onClosePanel?: () => void;
  readonly className?: string;
}

/** The list region the toggle controls. */
const LIST_ID = "side-navigation-list";

const NO_GROUPS: ReadonlySet<string> = new Set();

const LINK_CLASS = [
  "relative flex h-9 items-center rounded-md px-3 text-sm text-sidebar-fg hover:bg-sidebar-hover-bg",
  "focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sidebar-focus",
].join(" ");

// The current page is told by three things at once, never by color alone: aria-current, a heavier
// weight and a 3 px bar on the left (docs/ui-ux/07 section 6.1).
const CURRENT_CLASS =
  "bg-sidebar-active-bg font-semibold text-sidebar-active-fg before:absolute before:inset-y-1.5 before:left-0 before:w-0.75 before:rounded-full before:bg-sidebar-indicator";

function NavigationLinks({
  items,
  onNavigate,
}: Pick<SideNavigationProps, "items" | "onNavigate">) {
  return items.map((item) => (
    <li key={item.id}>
      <a
        href={item.href}
        aria-current={item.current ? "page" : undefined}
        onClick={(event) => {
          // A modified click keeps its browser meaning (new tab, new window).
          if (
            event.defaultPrevented ||
            event.button !== 0 ||
            event.metaKey ||
            event.ctrlKey ||
            event.shiftKey ||
            event.altKey
          )
            return;
          event.preventDefault();
          onNavigate(item.href);
        }}
        className={[LINK_CLASS, item.current ? CURRENT_CLASS : ""].join(" ")}
      >
        {item.label}
      </a>
    </li>
  ));
}

// A section is a disclosure: its header is a button that shows or hides the list. The list stays in the
// document (so aria-controls always points at something) and `hidden` takes it out of the tab order and
// the accessibility tree while it is collapsed.
function Group({
  group,
  expanded,
  onToggle,
  onNavigate,
}: {
  readonly group: SideNavigationGroup;
  readonly expanded: boolean;
  readonly onToggle: () => void;
  readonly onNavigate: (href: string) => void;
}) {
  const buttonId = useId();
  const listId = useId();
  return (
    <div className="flex flex-col pt-3">
      <button
        type="button"
        id={buttonId}
        aria-expanded={expanded}
        aria-controls={listId}
        onClick={onToggle}
        className={[
          "flex h-8 w-full items-center justify-between rounded-md px-3 text-left text-xs font-semibold tracking-caps text-sidebar-fg-muted uppercase",
          "hover:bg-sidebar-hover-bg hover:text-sidebar-fg",
          "focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sidebar-focus",
        ].join(" ")}
      >
        {group.label}
        <ChevronDown
          aria-hidden="true"
          className={[
            "size-4 shrink-0 transition-transform duration-150 motion-reduce:transition-none",
            expanded ? "rotate-180" : "",
          ].join(" ")}
        />
      </button>
      <ul
        id={listId}
        aria-labelledby={buttonId}
        hidden={!expanded}
        className="mt-0.5 flex flex-col gap-0.5"
      >
        <NavigationLinks items={group.items} onNavigate={onNavigate} />
      </ul>
    </div>
  );
}

/**
 * The main navigation: a navy landmark with the product name, the entries without a section and then one
 * labelled list per section. Links keep their `href`, so a modified click (new tab) and a no-script visit
 * still work.
 */
export function SideNavigation({
  items,
  groups = [],
  onNavigate,
  expandedGroupIds = NO_GROUPS,
  onToggleGroup,
  collapsed = false,
  onToggleCollapsed,
  onClosePanel,
  className,
}: SideNavigationProps) {
  const { t } = useAdminText();

  return (
    <nav
      aria-label={t("shell.navigationLabel")}
      className={[
        "flex h-full w-60 shrink-0 flex-col overflow-hidden border-r border-sidebar-border bg-sidebar-bg whitespace-nowrap",
        "lg:transition-[width] lg:duration-200 lg:ease-standard",
        collapsed ? "lg:w-16" : "",
        className ?? "",
      ].join(" ")}
    >
      {/* The logo is dark navy on transparent, so its strip is white in both themes (the sidebar-active white is fixed).
          In the rail a fixed box shows only the left part of the image, the emblem; the alt text stays. */}
      <div
        className={[
          "flex h-18 shrink-0 items-center justify-between gap-2 bg-sidebar-active-fg px-4",
          collapsed ? "lg:justify-center lg:px-0" : "",
        ].join(" ")}
      >
        <div className={["min-w-0 overflow-hidden", collapsed ? "lg:h-8 lg:w-11" : ""].join(" ")}>
          <img
            src={confiaLogo}
            alt={t("shell.logoAlt")}
            width={720}
            height={181}
            className={["block h-12 w-auto max-w-none", collapsed ? "lg:h-8" : ""].join(" ")}
          />
        </div>
        {onClosePanel === undefined ? null : (
          <button
            type="button"
            className="inline-flex size-9 shrink-0 items-center justify-center rounded-md text-sidebar-bg hover:bg-black/5 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sidebar-bg lg:hidden"
            aria-label={t("shell.closeSidebar")}
            title={t("shell.closeSidebar")}
            onClick={onClosePanel}
          >
            <X aria-hidden="true" className="size-5" />
          </button>
        )}
      </div>
      {/* Collapsed, the list leaves with display: none, so it is out of the tab order and the accessibility tree. The
          scroll belongs to the list, so the footer stays pinned to the bottom of the column. */}
      <div
        id={LIST_ID}
        className={["flex min-h-0 flex-1 flex-col gap-1 overflow-y-auto px-3 pb-4", collapsed ? "lg:hidden" : ""].join(" ")}
      >
        <ul className="flex flex-col gap-0.5">
          <NavigationLinks items={items} onNavigate={onNavigate} />
        </ul>
        {groups.map((group) => (
          <Group
            key={group.id}
            group={group}
            expanded={expandedGroupIds.has(group.id)}
            onToggle={() => {
              onToggleGroup?.(group.id);
            }}
            onNavigate={onNavigate}
          />
        ))}
      </div>
      {/* One toggle for both states: the same element keeps the focus when it changes. Wide screens only. */}
      <div className="mt-auto hidden shrink-0 border-t border-sidebar-border p-3 lg:flex">
        <button
          type="button"
          aria-expanded={!collapsed}
          aria-controls={LIST_ID}
          aria-label={t(collapsed ? "shell.expandSidebar" : "shell.collapseSidebar")}
          title={t(collapsed ? "shell.expandSidebar" : "shell.collapseSidebar")}
          onClick={onToggleCollapsed}
          className={[
            "inline-flex items-center rounded-md text-sidebar-fg transition-colors duration-100",
            "hover:bg-sidebar-hover-bg active:bg-sidebar-active-bg active:text-sidebar-active-fg",
            "focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sidebar-focus",
            collapsed ? "size-10 justify-center" : "h-10 w-full justify-start gap-2 px-3 text-sm",
          ].join(" ")}
        >
          {collapsed ? <PanelLeftOpen aria-hidden="true" className="size-5 shrink-0" /> : <PanelLeftClose aria-hidden="true" className="size-5 shrink-0" />}
          {collapsed ? null : t("shell.collapseSidebar")}
        </button>
      </div>
    </nav>
  );
}
