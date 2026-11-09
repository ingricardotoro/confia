import { Alert, Button, FormErrorSummary, FormField, Input } from "@confia/ui";
import type { AccessFailure } from "../shared/forms/access-failure.ts";
import { useAccessForm, type FieldReason } from "../shared/forms/use-access-form.ts";

type HarnessField = "identifier" | "password";

export interface AccessFormHarnessProps {
  readonly submit: (values: Readonly<Record<HarnessField, string>>) => Promise<AccessFailure | undefined>;
  /** Defaults to "required" for an empty field. */
  readonly validate?: (values: Readonly<Record<HarnessField, string>>) => Partial<Record<HarnessField, FieldReason>>;
  /** The visible texts, written by the test: a harness has no catalog of its own. */
  readonly labels: { readonly identifier: string; readonly password: string; readonly submit: string; readonly live: string };
}

const requireBoth: NonNullable<AccessFormHarnessProps["validate"]> = (values) => ({
  ...(values.identifier === "" ? { identifier: "required" as const } : {}),
  ...(values.password === "" ? { password: "required" as const } : {}),
});

/** A form that only composes `useAccessForm`, so the hook is tested through what a screen would render. */
export function AccessFormHarness({ submit, validate = requireBoth, labels }: AccessFormHarnessProps) {
  const { summaryRef, alertRef, ...form } = useAccessForm<HarnessField>({
    fields: {
      identifier: { controlId: "harness-identifier", normalize: (raw) => raw.trim() },
      password: { controlId: "harness-password" },
    },
    validate,
    submit,
    waitingKey: "signIn.waiting",
  });

  return (
    <form noValidate onSubmit={form.onSubmit}>
      <FormErrorSummary ref={summaryRef} errors={form.summary} />
      {form.alert === undefined ? null : (
        <Alert ref={alertRef} variant="destructive">
          {form.alert.message}
          {form.alert.traceId === undefined ? null : <span>{form.alert.traceId}</span>}
        </Alert>
      )}
      <FormField label={labels.identifier} controlId="harness-identifier" required {...errorOf(form.fieldErrors.identifier)}>
        {(control) => (
          <Input
            {...control}
            value={form.values.identifier}
            onChange={(event) => {
              form.onChange("identifier", event.target.value);
            }}
            onBlur={() => {
              form.onBlur("identifier");
            }}
          />
        )}
      </FormField>
      <FormField label={labels.password} controlId="harness-password" required {...errorOf(form.fieldErrors.password)}>
        {(control) => (
          <Input
            {...control}
            type="password"
            value={form.values.password}
            onChange={(event) => {
              form.onChange("password", event.target.value);
            }}
            onBlur={() => {
              form.onBlur("password");
            }}
          />
        )}
      </FormField>
      <div role="status" aria-label={labels.live}>
        {form.liveMessage}
      </div>
      <Button type="submit" loading={form.submitting}>
        {labels.submit}
      </Button>
    </form>
  );
}

function errorOf(error: string | undefined): { error?: string } {
  return error === undefined ? {} : { error };
}
