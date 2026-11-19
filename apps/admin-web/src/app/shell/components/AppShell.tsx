import { Alert, Button } from "@confia/ui";
import { PanelLeftClose, PanelLeftOpen, X } from "lucide-react";
import { useEffect, useRef, useState, type ReactNode } from "react";
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
  /** Whether the navigation is shown on wide screens. The caller owns where the choice is kept. Default: shown. */
  readonly sidebarVisible?: boolean;
  readonly onSidebarVisibleChange?: (visible: boolean) => void;
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
  sidebarVisible = true,
  onSidebarVisibleChange,
  signingOut,
  signOutFailed,
  onSignOut,
  children,
}: AppShellProps) {
  const { t } = useAdminText();
  const [menuOpen, setMenuOpen] = useState(false);
  const sidebarToggle = useRef<HTMLButtonElement>(null);

  const changeSidebarVisible = (visible: boolean) => {
    onSidebarVisibleChange?.(visible);
    // The control that was pressed may be about to leave the page: the focus goes to the one that always stays.
    sidebarToggle.current?.focus();
  };

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
        // Below lg: a panel over the page. From lg: a column that stays in view while the page scrolls (sticky, one
        // viewport tall) so the page never grows a band below the content; hidden, it leaves the layout, the tab order
        // and the accessibility tree (display: none).
        className={`${menuOpen ? "fixed inset-y-0 left-0 z-40 flex shadow-xl" : "hidden"} lg:sticky lg:top-0 lg:z-auto lg:h-dvh lg:self-start lg:shadow-none ${sidebarVisible ? "lg:flex" : "lg:hidden"}`}
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
          headerAction={
            <>
              <Button
                variant="subtle"
                size="sm"
                className="shrink-0 px-2 lg:hidden"
                aria-label={t("shell.closeSidebar")}
                title={t("shell.closeSidebar")}
                onClick={() => {
                  setMenuOpen(false);
                }}
              >
                <X aria-hidden="true" />
              </Button>
              <Button
                variant="subtle"
                size="sm"
                className="hidden shrink-0 px-2 lg:inline-flex"
                aria-label={t("shell.hideSidebar")}
                title={t("shell.hideSidebar")}
                onClick={() => {
                  changeSidebarVisible(false);
                }}
              >
                <PanelLeftClose aria-hidden="true" />
              </Button>
            </>
          }
          className="h-full"
        />
      </div>
      <div className="flex min-h-dvh min-w-0 flex-1 flex-col">
        <header className="flex h-14 shrink-0 items-center justify-between gap-3 border-b border-border-subtle bg-bg-surface px-6">
          <Button
            ref={sidebarToggle}
            variant="subtle"
            size="sm"
            className="hidden px-2 lg:inline-flex"
            aria-expanded={sidebarVisible}
            aria-controls="side-navigation"
            aria-label={t(sidebarVisible ? "shell.hideSidebar" : "shell.showSidebar")}
            title={t(sidebarVisible ? "shell.hideSidebar" : "shell.showSidebar")}
            onClick={() => {
              changeSidebarVisible(!sidebarVisible);
            }}
          >
            {sidebarVisible ? <PanelLeftClose aria-hidden="true" /> : <PanelLeftOpen aria-hidden="true" />}
          </Button>
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
