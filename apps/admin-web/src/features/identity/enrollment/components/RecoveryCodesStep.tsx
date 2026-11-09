import { Button, Checkbox, FormErrorSummary, FormField } from "@confia/ui";
import { useEffect, useRef, type Ref } from "react";
import { useAdminText } from "../../../../i18n/use-admin-text.ts";
import { ENROLLMENT_CONTROL_IDS } from "../enrollment-controls.ts";

export interface RecoveryCodesStepProps {
  /** Shown as text, once: the container forgets them when the person advances. */
  readonly codes: readonly string[];
  readonly confirmed: boolean;
  /** The failed confirmation, already translated; empty until Continue was pressed without it. */
  readonly summary: readonly { readonly controlId: string; readonly message: string }[];
  /** The message under the checkbox, already translated. */
  readonly error?: string;
  readonly summaryRef: Ref<HTMLDivElement>;
  readonly onConfirmedChange: (confirmed: boolean) => void;
  readonly onContinue: () => void;
}

/**
 * The second section of the enrollment (design.md E10): the recovery codes as the text of a list, a checkbox that
 * confirms they were saved, and Continue, which is never disabled (SS90). It offers no way to download, print or send
 * them (SS95): a code leaves the screen only as the person's own copy of the text.
 */
export function RecoveryCodesStep({ codes, confirmed, summary, error, summaryRef, onConfirmedChange, onContinue }: RecoveryCodesStepProps) {
  const { t } = useAdminText();
  const heading = useRef<HTMLHeadingElement>(null);

  // Each section takes the focus on its own heading when it appears (D11).
  useEffect(() => {
    heading.current?.focus();
  }, []);

  return (
    <section className="flex flex-col gap-4">
      <h2 ref={heading} tabIndex={-1} className="text-lg font-semibold text-text-primary outline-none">
        {t("enrollment.codes.title")}
      </h2>
      <p className="text-text-secondary">{t("enrollment.codes.intro")}</p>
      <ul className="grid grid-cols-2 gap-x-4 gap-y-2 rounded-md border border-border-subtle p-4 font-mono text-base tracking-wide text-text-primary">
        {codes.map((code) => (
          <li key={code}>{code}</li>
        ))}
      </ul>
      <form
        noValidate
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault();
          onContinue();
        }}
      >
        <FormErrorSummary ref={summaryRef} errors={summary} />
        <FormField
          label={t("enrollment.codes.confirm")}
          controlId={ENROLLMENT_CONTROL_IDS.confirmCodes}
          required
          {...(error === undefined ? {} : { error })}
        >
          {(control) => (
            <Checkbox
              {...control}
              checked={confirmed}
              onCheckedChange={(checked) => {
                onConfirmedChange(checked === true);
              }}
            />
          )}
        </FormField>
        <Button type="submit">{t("enrollment.continue")}</Button>
      </form>
    </section>
  );
}
