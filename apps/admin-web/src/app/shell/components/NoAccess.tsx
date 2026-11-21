import { Alert } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { PageContent } from "../../../shared/layout/components/PageContent.tsx";

export interface NoAccessProps {
  /** Where the link points, so it works without script and can be opened in a new tab. */
  readonly homeHref: string;
  /** Called on a plain click, which the caller turns into a navigation of the router. */
  readonly onNavigate: () => void;
}

/**
 * The page a person reaches by address without the permission it needs. It says so and offers the way back to
 * the start. The panel only hides: if the person had the permission, the server would still decide each request.
 */
export function NoAccess({ homeHref, onNavigate }: NoAccessProps) {
  const { t } = useAdminText();

  return (
    <PageContent title={t("noAccess.title")}>
      <Alert variant="info">
        <p>{t("noAccess.message")}</p>
      </Alert>
      <a
        href={homeHref}
        onClick={(event) => {
          // A modified click keeps its browser meaning (new tab, new window).
          if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
          event.preventDefault();
          onNavigate();
        }}
        className="w-fit text-text-link underline"
      >
        {t("noAccess.link")}
      </a>
    </PageContent>
  );
}
