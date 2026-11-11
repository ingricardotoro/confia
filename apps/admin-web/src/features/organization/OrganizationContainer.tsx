import { useAdminText } from "../../i18n/use-admin-text.ts";
import { PageContent } from "../../shared/layout/components/PageContent.tsx";

/**
 * The academic structure area. For now a protected placeholder, so the navigation leads somewhere; the
 * four views arrive with the contract of /api/v1/organization.
 */
export function OrganizationContainer() {
  const { t } = useAdminText();

  return (
    <PageContent title={t("organization.title")}>
      <p className="text-text-secondary">{t("organization.placeholder")}</p>
    </PageContent>
  );
}
