import { Alert, Button, ErrorState, Spinner } from "@confia/ui";
import { useAdminText } from "../../../../i18n/use-admin-text.ts";
import { ScreenLayout } from "../../components/ScreenLayout.tsx";

export type SignedInPageProps =
  | { readonly status: "loading" }
  /** `message` arrives already translated; the retry is required, an error with no retry is a dead end. */
  | { readonly status: "error"; readonly message: string; readonly onRetry: () => void }
  | {
      readonly status: "ready";
      readonly signingOut: boolean;
      readonly signOutFailed: boolean;
      readonly onSignOut: () => void;
    };

/**
 * The minimal signed in page (SS103 to SS110): a heading, the confirmation and the sign out button. It shows
 * nothing about the person. While signing out the button is busy and stays enabled, so the focus is not
 * lost; the container ignores the clicks that come meanwhile.
 */
export function SignedInPage(props: SignedInPageProps) {
  const { t } = useAdminText();

  return (
    <ScreenLayout title={t("signedIn.title")}>
      {props.status === "loading" ? (
        <div className="flex items-center gap-3">
          <Spinner />
          <p role="status" aria-live="polite" className="text-text-secondary">
            {t("signedIn.loading")}
          </p>
        </div>
      ) : null}
      {props.status === "error" ? <ErrorState title={props.message} onRetry={props.onRetry} /> : null}
      {props.status === "ready" ? (
        <>
          <p className="text-text-secondary">{t("signedIn.message")}</p>
          {props.signOutFailed ? <Alert variant="destructive">{t("signedIn.signOutFailed")}</Alert> : null}
          <Button variant="secondary" loading={props.signingOut} onClick={props.onSignOut}>
            {props.signOutFailed ? t("signedIn.retrySignOut") : t("signedIn.signOut")}
          </Button>
        </>
      ) : null}
    </ScreenLayout>
  );
}
