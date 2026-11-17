import { Outlet, useNavigate, useRouterState } from "@tanstack/react-router";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { PageContent } from "../../shared/layout/components/PageContent.tsx";
import { LedgerTabs } from "./components/LedgerTabs.tsx";
import { LEDGER_VIEWS, isCurrentLedgerView } from "./ledger-paths.ts";

/**
 * The area of the chart of accounts: the page heading, the sub navigation between its two views (the chart and the map of
 * posting accounts) and the view below. The view is the route's child, so the address names it and a link to it can be shared.
 */
export function LedgerContainer() {
  const { t } = useAdminText();
  const navigate = useNavigate();
  const pathname = useRouterState({ select: (state) => state.location.pathname });

  return (
    <PageContent title={t("ledger.title")}>
      <LedgerTabs
        tabs={LEDGER_VIEWS.map((view) => ({
          id: view.id,
          label: t(view.labelKey),
          href: view.path,
          current: isCurrentLedgerView(view, pathname),
        }))}
        onNavigate={(href) => void navigate({ to: href })}
      />
      <Outlet />
    </PageContent>
  );
}
