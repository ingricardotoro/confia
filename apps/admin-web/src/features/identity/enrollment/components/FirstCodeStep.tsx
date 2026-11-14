import { Button, FormErrorSummary, FormField, Input } from "@confia/ui";
import { useEffect, useRef, type Ref, type SyntheticEvent } from "react";
import { useAdminText } from "../../../../i18n/use-admin-text.ts";
import { CopyButton } from "../../components/CopyButton.tsx";
import { ProblemAlert } from "../../components/ProblemAlert.tsx";
import { ENROLLMENT_CONTROL_IDS } from "../enrollment-controls.ts";
import { groupManualKey } from "../manual-key.ts";
import { TotpQrCode } from "./TotpQrCode.tsx";

/** The one field of the step. */
export type FirstCodeField = "code";

/** What the step needs from the form behind it. Texts arrive already translated; nothing here reads data. */
export interface FirstCodeStepProps {
  /** The `otpauth` URI the QR code draws. */
  readonly uri: string;
  /** Base32, as it arrived: the key stays in view until the activation (SS97). */
  readonly secret: string;
  readonly values: Readonly<Record<FirstCodeField, string>>;
  readonly fieldErrors: Readonly<Partial<Record<FirstCodeField, string>>>;
  readonly summary: readonly { readonly controlId: string; readonly message: string }[];
  readonly alert?: { readonly message: string; readonly traceId?: string };
  /** The wait after a 429, already translated, drawn outside every active region. */
  readonly countdown?: { readonly remaining: string } | undefined;
  /** The one polite region of the form. */
  readonly liveMessage: string;
  readonly submitting: boolean;
  readonly summaryRef: Ref<HTMLDivElement>;
  readonly alertRef: Ref<HTMLDivElement>;
  readonly onChange: (field: FirstCodeField, value: string) => void;
  readonly onBlur: (field: FirstCodeField) => void;
  readonly onSubmit: (event: SyntheticEvent) => void;
  readonly onReviewSecret: () => void;
}

/**
 * The third section of the enrollment (design.md E10): the field of the first code, with the QR code and the key still in
 * view. The field has no `maxLength` and no `pattern`: the schema decides, and a paste with spaces must arrive whole to be
 * normalized. The field exists only in this section, so it cannot be reached before the codes were confirmed (SS92).
 */
export function FirstCodeStep({
  uri,
  secret,
  values,
  fieldErrors,
  summary,
  alert,
  countdown,
  liveMessage,
  submitting,
  summaryRef,
  alertRef,
  onChange,
  onBlur,
  onSubmit,
  onReviewSecret,
}: FirstCodeStepProps) {
  const { t } = useAdminText();
  const heading = useRef<HTMLHeadingElement>(null);

  // Each section takes the focus on its own heading when it appears (D11).
  useEffect(() => {
    heading.current?.focus();
  }, []);

  return (
    <section className="flex flex-col gap-4">
      <h2 ref={heading} tabIndex={-1} className="text-lg font-semibold text-text-primary outline-none">
        {t("enrollment.firstCode.title")}
      </h2>
      <div className="flex justify-center">
        <TotpQrCode uri={uri} />
      </div>
      <div className="flex flex-col gap-2">
        <p className="text-sm text-text-secondary">{t("enrollment.manualKey")}</p>
        <p className="flex flex-wrap items-center gap-2">
          <code className="font-mono text-base tracking-wide text-text-primary">{groupManualKey(secret)}</code>
          <CopyButton text={secret} kind="key" />
        </p>
      </div>
      <form noValidate onSubmit={onSubmit} className="flex flex-col gap-4">
        <FormErrorSummary ref={summaryRef} errors={summary} />
        {alert === undefined ? null : <ProblemAlert ref={alertRef} message={alert.message} {...(alert.traceId === undefined ? {} : { traceId: alert.traceId })} />}
        {countdown === undefined ? null : <p className="text-sm text-text-secondary">{countdown.remaining}</p>}
        <FormField
          label={t("secondFactor.code")}
          controlId={ENROLLMENT_CONTROL_IDS.firstCode}
          required
          description={t("secondFactor.codeFormat")}
          {...(fieldErrors.code === undefined ? {} : { error: fieldErrors.code })}
        >
          {(control) => (
            <Input
              {...control}
              type="text"
              spellCheck={false}
              inputMode="numeric"
              autoComplete="one-time-code"
              value={values.code}
              onChange={(event) => {
                onChange("code", event.target.value);
              }}
              onBlur={() => {
                onBlur("code");
              }}
            />
          )}
        </FormField>
        <Button type="submit" loading={submitting}>
          {t("enrollment.firstCode.submit")}
        </Button>
        <p aria-live="polite" aria-atomic="true" className="text-sm text-text-secondary">
          {liveMessage}
        </p>
      </form>
      <Button type="button" variant="link" aria-disabled={submitting || undefined} onClick={onReviewSecret} className="self-start">
        {t("enrollment.firstCode.reviewSecret")}
      </Button>
    </section>
  );
}
