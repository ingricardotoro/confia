import { ScreenLayout } from "../../features/identity/components/ScreenLayout.tsx";
import { useAdminText } from "../../i18n/use-admin-text.ts";

export interface NotFoundProps {
  /** Where the link points, so it works without script and can be opened in a new tab. */
  readonly href: string;
  /** Called on a plain click, which the caller turns into a navigation of the router. */
  readonly onNavigate: () => void;
}

/** A route that does not exist: a heading and a way back, in the text of the catalog and not in the library text. */
export function NotFound({ href, onNavigate }: NotFoundProps) {
  const { t } = useAdminText();

  return (
    <ScreenLayout title={t("notFound.title")}>
      <a
        href={href}
        onClick={(event) => {
          // A modified click keeps its browser meaning (new tab, new window).
          if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
          event.preventDefault();
          onNavigate();
        }}
        className="text-text-link underline"
      >
        {t("notFound.link")}
      </a>
    </ScreenLayout>
  );
}
