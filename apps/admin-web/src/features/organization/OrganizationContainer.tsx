import { Outlet, useNavigate, useRouterState } from "@tanstack/react-router";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { PageContent } from "../../shared/layout/components/PageContent.tsx";
import { isCurrentEntry } from "../../shared/navigation/navigation-entry.ts";
import { OrganizationTabs } from "./components/OrganizationTabs.tsx";
import { ORGANIZATION_VIEWS } from "./organization-paths.ts";

/**
 * The academic structure area: the page heading, the sub navigation between its four views, and the view
 * below. The view is the route's child, so the address names it and a link to it can be shared.
 */
export function OrganizationContainer() {
  const { t } = useAdminText();
  const navigate = useNavigate();
  const pathname = useRouterState({ select: (state) => state.location.pathname });

  return (
    <PageContent title={t("organization.title")}>
      <OrganizationTabs
        tabs={ORGANIZATION_VIEWS.map((view) => ({
          id: view.id,
          label: t(view.labelKey),
          href: view.path,
          current: isCurrentEntry({ to: view.path }, pathname),
        }))}
        onNavigate={(href) => void navigate({ to: href })}
      />
      <Outlet />
    </PageContent>
  );
}
