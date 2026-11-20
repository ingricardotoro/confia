import { Alert, Button } from "@confia/ui";
import { useEffect, useState, type ReactNode } from "react";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import {
  SideNavigation,
  type SideNavigationGroup,
  type SideNavigationItem,
} from "./SideNavigation.tsx";

export interface AppShellProps {
  readonly navigation: readonly SideNavigationItem[];
  /** The labelled sections of the navigation, below the entries that have none. */
  readonly navigationGroups?: readonly SideNavigationGroup[];
  readonly onNavigate: (href: string) => void;
  /** The ids of the open sections of the navigation. */
  readonly expandedGroupIds?: ReadonlySet<string>;
  readonly onToggleGroup?: (groupId: string) => void;
  /** Whether the navigation is a slim rail on wide screens. The caller owns where the choice is kept. Default: expanded. */
  readonly sidebarCollapsed?: boolean;
  readonly onSidebarCollapsedChange?: (collapsed: boolean) => void;
  readonly signingOut: boolean;
  readonly signOutFailed: boolean;
  readonly onSignOut: () => void;
  /** The page: the outlet of the router. */
  readonly children: ReactNode;
}

/**
 * The frame of every signed in page: a skip link, the header with the account area and the sign out,
 * the side navigation and the `main` region. While signing out the button is busy and stays enabled,
 * so the focus is not lost; the container ignores the clicks that come meanwhile. It shows nothing
 * about the person: the session read keeps its metadata in the query cache and out of the screen.
 */
export function AppShell({
  navigation,
  navigationGroups,
  onNavigate,
  expandedGroupIds,
  onToggleGroup,
  sidebarCollapsed = false,
  onSidebarCollapsedChange,
  signingOut,
  signOutFailed,
  onSignOut,
  children,
}: AppShellProps) {
  const { t } = useAdminText();
  const [menuOpen, setMenuOpen] = useState(false);

  // Below lg the sidebar is a panel the person opens; Escape closes it.
  useEffect(() => {
    if (!menuOpen) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") setMenuOpen(false);
    };
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("keydown", onKey);
    };
  }, [menuOpen]);

  return (
    <div className="flex min-h-dvh bg-bg-canvas text-text-primary">
      <a
        href="#contenido"
        className="sr-only focus:not-sr-only focus:absolute focus:top-2 focus:left-2 focus:z-50 focus:rounded-md focus:bg-bg-surface focus:px-3 focus:py-2 focus:text-text-link focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-border-focus"
      >
        {t("shell.skipToContent")}
      </a>
      <div
        id="side-navigation"
        // Below lg: a panel over the page. From lg: a column that never disappears and stays in view while the page
        // scrolls (sticky, one viewport tall), so the page never grows a band below the content. Its width is the
        // navigation's: full, or the slim rail.
        className={`${menuOpen ? "fixed inset-y-0 left-0 z-40 flex shadow-xl" : "hidden"} lg:sticky lg:top-0 lg:z-auto lg:flex lg:h-dvh lg:self-start lg:shadow-none`}
      >
        <SideNavigation
          items={navigation}
          {...(navigationGroups === undefined
            ? {}
            : { groups: navigationGroups })}
          {...(expandedGroupIds === undefined ? {} : { expandedGroupIds })}
          {...(onToggleGroup === undefined ? {} : { onToggleGroup })}
          onNavigate={(href) => {
            setMenuOpen(false);
            onNavigate(href);
          }}
          collapsed={sidebarCollapsed}
          onToggleCollapsed={() => {
            onSidebarCollapsedChange?.(!sidebarCollapsed);
          }}
          onClosePanel={() => {
            setMenuOpen(false);
          }}
          className="h-full"
        />
      </div>
      <div className="flex min-h-dvh min-w-0 flex-1 flex-col">
        <header className="flex h-14 shrink-0 items-center justify-between gap-3 border-b border-border-subtle bg-bg-surface px-6">
          <Button
            variant="subtle"
            size="sm"
            className="lg:hidden"
            aria-expanded={menuOpen}
            aria-controls="side-navigation"
            onClick={() => {
              setMenuOpen((open) => !open);
            }}
          >
            {menuOpen ? t("shell.closeNavigation") : t("shell.openNavigation")}
          </Button>
          <div
            role="group"
            aria-label={t("shell.accountLabel")}
            className="ml-auto flex flex-wrap items-center gap-3"
          >
            {signOutFailed ? (
              <Alert variant="destructive">{t("signedIn.signOutFailed")}</Alert>
            ) : null}
            <Button
              variant="subtle"
              size="sm"
              loading={signingOut}
              onClick={onSignOut}
            >
              {signOutFailed
                ? t("signedIn.retrySignOut")
                : t("signedIn.signOut")}
            </Button>
          </div>
        </header>
        <main
          id="contenido"
          tabIndex={-1}
          className="min-w-0 flex-1 px-6 py-6 xl:px-8"
        >
          {children}
        </main>
      </div>
    </div>
  );
}
