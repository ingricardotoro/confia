import { Outlet } from "@tanstack/react-router";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { PageContent } from "../../shared/layout/components/PageContent.tsx";

/**
 * The payments area: the page heading and the view below. The view is the route child, so the address names it and a link to it can be shared:
 * the list of payments at the address of the area, the register of a payment and the detail of one below it.
 */
export function PaymentsAreaContainer() {
  const { t } = useAdminText();
  return (
    <PageContent title={t("payments.title")}>
      <Outlet />
    </PageContent>
  );
}
