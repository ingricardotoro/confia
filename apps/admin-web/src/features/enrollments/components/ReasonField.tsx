import { FormField, Textarea } from "@confia/ui";

export interface ReasonFieldProps {
  readonly label: string;
  readonly controlId: string;
  readonly value: string;
  readonly onChange: (value: string) => void;
  readonly onBlur: () => void;
  readonly error?: string | undefined;
  readonly description: string;
  readonly required?: boolean;
}

/**
 * A reason in the person's own words (3 to 500 characters). It can name a family or a health matter, so the browser must
 * not spell-check, autocorrect or autofill it: those can send it to other services.
 */
export function ReasonField({ label, controlId, value, onChange, onBlur, error, description, required = false }: ReasonFieldProps) {
  return (
    <FormField label={label} controlId={controlId} required={required} description={description} {...(error === undefined ? {} : { error })}>
      {(control) => (
        <Textarea
          {...control}
          rows={3}
          maxLength={500}
          autoComplete="off"
          autoCorrect="off"
          spellCheck={false}
          value={value}
          onChange={(event) => {
            onChange(event.target.value);
          }}
          onBlur={onBlur}
        />
      )}
    </FormField>
  );
}
