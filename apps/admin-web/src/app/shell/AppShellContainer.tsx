import {
  Navigate,
  Outlet,
  useNavigate,
  useRouterState,
} from "@tanstack/react-router";
import { SignedInPage } from "../../features/identity/signed-in/components/SignedInPage.tsx";
import { useSignedInSession } from "../../features/identity/signed-in/use-signed-in-session.ts";
import type { AccessSession } from "../../features/identity/session/index.ts";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import {
  isCurrentEntry,
  NAVIGATION_GROUP_ORDER,
  type NavigationEntry,
} from "../../shared/navigation/navigation-entry.ts";
import { AppShell } from "./components/AppShell.tsx";
import { useExpandedGroups } from "./use-expanded-groups.ts";
import { useSidebarVisible } from "./use-sidebar-visible.ts";

export interface AppShellContainerProps {
  readonly session: AccessSession;
  /** Where the person goes when the session is over, by any road. A prop, so this file does not import the routes. */
  readonly signInPath: string;
  readonly entries: readonly NavigationEntry[];
}

interface ShellFrameProps {
  readonly entries: readonly NavigationEntry[];
  readonly signingOut: boolean;
  readonly signOutFailed: boolean;
  readonly onSignOut: () => void;
}

// The router location is read here and not in the container: a change of location must not re-render the
// component that holds the session query, or leaving the shell would rebuild a query the sign out just
// emptied from the cache.
function ShellFrame({
  entries,
  signingOut,
  signOutFailed,
  onSignOut,
}: ShellFrameProps) {
  const { t } = useAdminText();
  const navigate = useNavigate();
  const pathname = useRouterState({
    select: (state) => state.location.pathname,
  });

  const toItem = (entry: NavigationEntry) => ({
    id: entry.id,
    label: t(entry.labelKey),
    href: entry.to,
    current: isCurrentEntry(entry, pathname),
  });
  const groups = NAVIGATION_GROUP_ORDER.flatMap((id) => {
    const inGroup = entries.filter((entry) => entry.group === id);
    return inGroup.length === 0
      ? []
      : [
          {
            id,
            label: t(`navigation.groups.${id}`),
            items: inGroup.map(toItem),
          },
        ];
  });

  const activeGroupId = groups.find((group) =>
    group.items.some((item) => item.current),
  )?.id;
  const { expandedGroupIds, toggleGroup } = useExpandedGroups(activeGroupId);
  const { sidebarVisible, setSidebarVisible } = useSidebarVisible();

  return (
    <AppShell
      navigation={entries
        .filter((entry) => entry.group === undefined)
        .map(toItem)}
      navigationGroups={groups}
      expandedGroupIds={expandedGroupIds}
      onToggleGroup={toggleGroup}
      sidebarVisible={sidebarVisible}
      onSidebarVisibleChange={setSidebarVisible}
      onNavigate={(href) => void navigate({ to: href })}
      signingOut={signingOut}
      signOutFailed={signOutFailed}
      onSignOut={onSignOut}
    >
      <Outlet />
    </AppShell>
  );
}

/**
 * The layout route of every signed in page: it reads the session, shows the loading and the error of
 * that read as the full page the person already knew, and once the session is confirmed it draws the
 * shell around the outlet.
 */
export function AppShellContainer({
  session,
  signInPath,
  entries,
}: AppShellContainerProps) {
  const { t } = useAdminText();
  const signedIn = useSignedInSession(session);

  if (!signedIn.authenticated) return <Navigate to={signInPath} replace />;
  if (signedIn.read === "error")
    return (
      <SignedInPage
        status="error"
        message={t(signedIn.errorKey)}
        onRetry={signedIn.retry}
      />
    );
  if (signedIn.read === "loading") return <SignedInPage status="loading" />;

  return (
    <ShellFrame
      entries={entries}
      signingOut={signedIn.signingOut}
      signOutFailed={signedIn.signOutFailed}
      onSignOut={signedIn.signOut}
    />
  );
}
