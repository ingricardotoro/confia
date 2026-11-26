import { Outlet } from "@tanstack/react-router";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { PageContent } from "../../shared/layout/components/PageContent.tsx";

/**
 * The scholarships area: the page heading and the view below. The view is the route child, so the address names it and a link to it can be shared:
 * the scholarships, the awards (and one award below them) and the simulator of charges.
 */
export function ScholarshipsAreaContainer() {
  const { t } = useAdminText();
  return (
    <PageContent title={t("scholarships.title")}>
      <Outlet />
    </PageContent>
  );
}
