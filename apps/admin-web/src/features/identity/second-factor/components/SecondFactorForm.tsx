import { Button, FormErrorSummary, FormField, Input } from "@confia/ui";
import { useEffect, useRef, type Ref, type SyntheticEvent } from "react";
import { useAdminText } from "../../../../i18n/use-admin-text.ts";
import { ProblemAlert } from "../../components/ProblemAlert.tsx";
import { ScreenLayout } from "../../components/ScreenLayout.tsx";
import { SECOND_FACTOR_CONTROL_IDS, type SecondFactorField, type SecondFactorMode } from "../second-factor-schema.ts";

/** What the screen needs from the form behind it. Texts arrive already translated; nothing here reads data. */
export interface SecondFactorFormProps {
  readonly mode: SecondFactorMode;
  readonly values: Readonly<Record<SecondFactorField, string>>;
  readonly fieldErrors: Readonly<Partial<Record<SecondFactorField, string>>>;
  readonly summary: readonly { readonly controlId: string; readonly message: string }[];
  readonly alert?: { readonly message: string; readonly traceId?: string };
  /** The wait after a 429, already translated. It is drawn outside every active region: a number that changes each second must not be announced. */
  readonly countdown?: { readonly remaining: string } | undefined;
  /** The one polite region of the form: the wait while a request is in flight, the end of a 429 wait, or nothing. */
  readonly liveMessage: string;
  readonly submitting: boolean;
  /** The person changed the mode, so the new field takes the focus. On arrival it stays false and the heading keeps it. */
  readonly focusField: boolean;
  readonly summaryRef: Ref<HTMLDivElement>;
  readonly alertRef: Ref<HTMLDivElement>;
  readonly onChange: (field: SecondFactorField, value: string) => void;
  readonly onBlur: (field: SecondFactorField) => void;
  readonly onSubmit: (event: SyntheticEvent) => void;
  readonly onToggleMode: () => void;
  readonly onBack: () => void;
}

/**
 * The verification screen (design.md E9). Exactly one field is mounted, the one of the mode, so what is sent can never
 * hold both (SS65). Neither has `maxLength` nor `pattern`: the schema decides, and a paste with spaces must arrive whole
 * to be normalized (SS62). The count of a 429 stays outside the polite region, as on the sign in screen.
 */
export function SecondFactorForm({
  mode,
  values,
  fieldErrors,
  summary,
  alert,
  countdown,
  liveMessage,
  submitting,
  focusField,
  summaryRef,
  alertRef,
  onChange,
  onBlur,
  onSubmit,
  onToggleMode,
  onBack,
}: SecondFactorFormProps) {
  const { t } = useAdminText();
  const input = useRef<HTMLInputElement>(null);
  const field: SecondFactorField = mode === "code" ? "code" : "recoveryCode";

  // After the effect of the layout, which focuses the heading when the screen mounts: a child's effects run first.
  useEffect(() => {
    if (focusField) input.current?.focus();
  }, [focusField]);

  return (
    <ScreenLayout title={t("secondFactor.title")}>
      <form noValidate onSubmit={onSubmit} className="flex flex-col gap-4">
        <FormErrorSummary ref={summaryRef} errors={summary} />
        {alert === undefined ? null : <ProblemAlert ref={alertRef} message={alert.message} {...(alert.traceId === undefined ? {} : { traceId: alert.traceId })} />}
        {countdown === undefined ? null : <p className="text-sm text-text-secondary">{countdown.remaining}</p>}
        <FormField
          label={t(mode === "code" ? "secondFactor.code" : "secondFactor.recoveryCode")}
          controlId={SECOND_FACTOR_CONTROL_IDS[field]}
          required
          {...(mode === "code" ? { description: t("secondFactor.codeFormat") } : {})}
          {...(fieldErrors[field] === undefined ? {} : { error: fieldErrors[field] })}
        >
          {(control) => (
            <Input
              {...control}
              ref={input}
              type="text"
              spellCheck={false}
              {...(mode === "code"
                ? { inputMode: "numeric" as const, autoComplete: "one-time-code" }
                : { autoComplete: "off", autoCapitalize: "characters" })}
              value={values[field]}
              onChange={(event) => {
                onChange(field, event.target.value);
              }}
              onBlur={() => {
                onBlur(field);
              }}
            />
          )}
        </FormField>
        <Button type="submit" loading={submitting}>
          {t("secondFactor.submit")}
        </Button>
        <p aria-live="polite" aria-atomic="true" className="text-sm text-text-secondary">
          {liveMessage}
        </p>
      </form>
      <div className="flex flex-col items-start gap-2">
        <Button type="button" variant="link" aria-disabled={submitting || undefined} onClick={onToggleMode}>
          {t(mode === "code" ? "secondFactor.useRecovery" : "secondFactor.useCode")}
        </Button>
        <Button type="button" variant="link" aria-disabled={submitting || undefined} onClick={onBack}>
          {t("secondFactor.back")}
        </Button>
      </div>
    </ScreenLayout>
  );
}
