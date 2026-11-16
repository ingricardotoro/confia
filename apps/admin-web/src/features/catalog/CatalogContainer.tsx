import { Outlet, useNavigate, useRouterState } from "@tanstack/react-router";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { PageContent } from "../../shared/layout/components/PageContent.tsx";
import { CATALOG_VIEWS, isCurrentCatalogView } from "./catalog-paths.ts";
import { CatalogTabs } from "./components/CatalogTabs.tsx";

/**
 * The catalog area: the page heading, the sub navigation between its views (the payment concepts and the price lists)
 * and the view below. The view is the route's child, so the address names it and a link to it can be shared.
 */
export function CatalogContainer() {
  const { t } = useAdminText();
  const navigate = useNavigate();
  const pathname = useRouterState({ select: (state) => state.location.pathname });

  return (
    <PageContent title={t("catalog.title")}>
      <CatalogTabs
        tabs={CATALOG_VIEWS.map((view) => ({
          id: view.id,
          label: t(view.labelKey),
          href: view.path,
          current: isCurrentCatalogView(view, pathname),
        }))}
        onNavigate={(href) => void navigate({ to: href })}
      />
      <Outlet />
    </PageContent>
  );
}
