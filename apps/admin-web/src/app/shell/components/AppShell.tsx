import { Alert, Button } from "@confia/ui";
import type { ReactNode } from "react";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { SideNavigation, type SideNavigationItem } from "./SideNavigation.tsx";

export interface AppShellProps {
  readonly navigation: readonly SideNavigationItem[];
  readonly onNavigate: (href: string) => void;
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
export function AppShell({ navigation, onNavigate, signingOut, signOutFailed, onSignOut, children }: AppShellProps) {
  const { t } = useAdminText();

  return (
    <div className="flex min-h-screen flex-col bg-bg-canvas text-text-primary">
      <a
        href="#contenido"
        className="sr-only focus:not-sr-only focus:absolute focus:top-2 focus:left-2 focus:z-50 focus:rounded-md focus:bg-bg-surface focus:px-3 focus:py-2 focus:text-text-link focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-border-focus"
      >
        {t("shell.skipToContent")}
      </a>
      <header className="flex flex-wrap items-center justify-between gap-3 border-b border-border-subtle bg-bg-surface px-4 py-2">
        <p className="text-lg font-semibold">{t("shell.brand")}</p>
        <div role="group" aria-label={t("shell.accountLabel")} className="flex flex-wrap items-center gap-3">
          {signOutFailed ? <Alert variant="destructive">{t("signedIn.signOutFailed")}</Alert> : null}
          <Button variant="secondary" size="sm" loading={signingOut} onClick={onSignOut}>
            {signOutFailed ? t("signedIn.retrySignOut") : t("signedIn.signOut")}
          </Button>
        </div>
      </header>
      <div className="flex flex-1">
        <SideNavigation items={navigation} onNavigate={onNavigate} />
        <main id="contenido" tabIndex={-1} className="min-w-0 flex-1 p-6">
          {children}
        </main>
      </div>
    </div>
  );
}
