import { Outlet } from "@tanstack/react-router";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { PageContent } from "../../shared/layout/components/PageContent.tsx";

/**
 * The charges area: the page heading and the view below. The view is the route's child, so the address names it and a
 * link to it can be shared. The area has one view today (the plans); the next cuts add theirs beside it.
 */
export function ChargesContainer() {
  const { t } = useAdminText();
  return (
    <PageContent title={t("charges.title")}>
      <Outlet />
    </PageContent>
  );
}
