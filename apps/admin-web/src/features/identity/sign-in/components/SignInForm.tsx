import { Alert, Button, FormErrorSummary, FormField, Input } from "@confia/ui";
import type { Ref, SyntheticEvent } from "react";
import { useAdminText } from "../../../../i18n/use-admin-text.ts";
import { ProblemAlert } from "../../components/ProblemAlert.tsx";
import { ScreenLayout } from "../../components/ScreenLayout.tsx";
import { SIGN_IN_CONTROL_IDS, type SignInField } from "../sign-in-schema.ts";
import { PasswordField } from "./PasswordField.tsx";

/** What the screen needs from the form behind it. Texts arrive already translated; nothing here reads data. */
export interface SignInFormProps {
  readonly values: Readonly<Record<SignInField, string>>;
  readonly fieldErrors: Readonly<Partial<Record<SignInField, string>>>;
  readonly summary: readonly { readonly controlId: string; readonly message: string }[];
  readonly alert?: { readonly message: string; readonly traceId?: string };
  /** Why the person arrived here when it was not by choice, and how serious it is. */
  readonly notice?: { readonly message: string; readonly tone: "info" | "warning" } | undefined;
  /** The wait after a 429, already translated. It is drawn outside every active region: a number that changes each second must not be announced. */
  readonly countdown?: { readonly remaining: string } | undefined;
  /** The one polite region of the form: the wait while a request is in flight, the end of a 429 wait, or nothing. */
  readonly liveMessage: string;
  readonly submitting: boolean;
  readonly summaryRef: Ref<HTMLDivElement>;
  readonly alertRef: Ref<HTMLDivElement>;
  readonly onChange: (field: SignInField, value: string) => void;
  readonly onBlur: (field: SignInField) => void;
  readonly onSubmit: (event: SyntheticEvent) => void;
}

/**
 * The sign in screen (design.md E8). The identifier is plain text, not `type="email"`: the browser may change or
 * refuse a value the server accepts, and it has to arrive as typed (SS52, SS58). There is no `maxLength` and no
 * `pattern`, so a long value can be pasted and the contract is the only limit (SS51). The contact text is a plain
 * paragraph: there is no recovery to link to (SS55). The form has one polite region for the wait and for its end, and
 * the count of a 429 stays outside it (SS44, SS48).
 */
export function SignInForm({
  values,
  fieldErrors,
  summary,
  alert,
  notice,
  countdown,
  liveMessage,
  submitting,
  summaryRef,
  alertRef,
  onChange,
  onBlur,
  onSubmit,
}: SignInFormProps) {
  const { t } = useAdminText();

  return (
    <ScreenLayout title={t("signIn.title")}>
      {notice === undefined ? null : <Alert variant={notice.tone}>{notice.message}</Alert>}
      <form noValidate onSubmit={onSubmit} className="flex flex-col gap-4">
        <FormErrorSummary ref={summaryRef} errors={summary} />
        {alert === undefined ? null : <ProblemAlert ref={alertRef} message={alert.message} {...(alert.traceId === undefined ? {} : { traceId: alert.traceId })} />}
        {countdown === undefined ? null : <p className="text-sm text-text-secondary">{countdown.remaining}</p>}
        <FormField
          label={t("signIn.identifier")}
          controlId={SIGN_IN_CONTROL_IDS.identifier}
          required
          {...(fieldErrors.identifier === undefined ? {} : { error: fieldErrors.identifier })}
        >
          {(control) => (
            <Input
              {...control}
              type="text"
              inputMode="email"
              autoComplete="username"
              autoCapitalize="none"
              spellCheck={false}
              value={values.identifier}
              onChange={(event) => {
                onChange("identifier", event.target.value);
              }}
              onBlur={() => {
                onBlur("identifier");
              }}
            />
          )}
        </FormField>
        <PasswordField
          label={t("signIn.password")}
          controlId={SIGN_IN_CONTROL_IDS.password}
          value={values.password}
          onChange={(value) => {
            onChange("password", value);
          }}
          onBlur={() => {
            onBlur("password");
          }}
          {...(fieldErrors.password === undefined ? {} : { error: fieldErrors.password })}
        />
        <Button type="submit" loading={submitting}>
          {t("signIn.submit")}
        </Button>
        <p aria-live="polite" aria-atomic="true" className="text-sm text-text-secondary">
          {liveMessage}
        </p>
      </form>
      <p className="text-sm text-text-secondary">{t("signIn.contact")}</p>
    </ScreenLayout>
  );
}
