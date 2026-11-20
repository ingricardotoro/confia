import { Outlet } from "@tanstack/react-router";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { PageContent } from "../../shared/layout/components/PageContent.tsx";

/**
 * The cashbox area: the page heading and the view below. The view is the route's child, so the address names it and a link to it can be
 * shared: the cashier's own cashbox at the area's address, the sessions list and one session's report below it.
 */
export function CashboxContainer() {
  const { t } = useAdminText();
  return (
    <PageContent title={t("cashbox.title")}>
      <Outlet />
    </PageContent>
  );
}
