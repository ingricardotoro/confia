import { ErrorState, Spinner } from "@confia/ui";
import { useAdminText } from "../../../../i18n/use-admin-text.ts";
import { ScreenLayout } from "../../components/ScreenLayout.tsx";

/** A failure always carries its retry: an error with no retry is a dead end. */
export type BootScreenProps =
  | { readonly status: "restoring" }
  | { readonly status: "failed"; readonly onRetry: () => void };

/** The screen of the boot, while the session is restored and when that failed by network (SS34, SS35). */
export function BootScreen(props: BootScreenProps) {
  const { t } = useAdminText();

  return (
    <ScreenLayout title={t("boot.title")}>
      {props.status === "restoring" ? (
        <div className="flex items-center gap-3">
          <Spinner />
          <p role="status" aria-live="polite" className="text-text-secondary">
            {t("boot.restoring")}
          </p>
        </div>
      ) : (
        <ErrorState title={t("boot.failed")} onRetry={props.onRetry} />
      )}
    </ScreenLayout>
  );
}
