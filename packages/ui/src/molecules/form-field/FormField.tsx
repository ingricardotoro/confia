import { CircleAlert } from "lucide-react";
import { useId, type ComponentProps, type ReactNode } from "react";
import { Label } from "../../atoms/label";
import { useUiText } from "../../i18n/use-ui-text";
import { cn } from "../../lib/cn";

/**
 * The props that tie a control to its field. The field computes them and the caller spreads them on
 * the control (Input, Textarea, Checkbox or RadioGroup), so the association cannot be forgotten or
 * misspelled. A key is absent, not `undefined`, when it does not apply: a field without an error
 * sets no `aria-invalid` at all.
 */
export interface FormFieldControl {
  /** Target of the label's `htmlFor`. */
  id: string;
  /** Names the control from the label. A radio group is a `div`, so `htmlFor` alone cannot name it. */
  "aria-labelledby": string;
  /** The description and the error, in that order. Absent when the field has neither. */
  "aria-describedby"?: string;
  /** Present, as `true`, only while the field has an error. */
  "aria-invalid"?: true;
  /** Present, as `true`, only on a required field. */
  "aria-required"?: true;
  /** Present, as `true`, only on a disabled field. */
  disabled?: true;
}

export interface FormFieldProps extends Omit<ComponentProps<"div">, "children"> {
  /** The visible label. A field without one has no name, which docs/ui-ux/03 section 5 forbids. */
  label: string;
  /** Help shown under the control and read after the label. Empty or blank text is no description. */
  description?: string;
  /** The message of the current error. Empty or blank text is no error. */
  error?: string;
  /**
   * The `id` of the control, when something outside the field must name it, as a FormErrorSummary link
   * does. Without it the field generates one.
   */
  controlId?: string;
  /** Marks the field as required, in text and for assistive technology. */
  required?: boolean;
  disabled?: boolean;
  /** Receives the association props and renders the control. */
  children: (control: FormFieldControl) => ReactNode;
}

// docs/ui-ux/03 section 5: a visible label tied by identifier, an error in text (not only color) tied
// with aria-describedby, and aria-invalid on the control. The label comes first and the control
// after it, so the dimming of a disabled field goes through Label's `disabled` prop and not through
// `peer-disabled`, which only reaches a preceding sibling (see Label).
export function FormField({
  label,
  description,
  error,
  controlId: givenControlId,
  required = false,
  disabled = false,
  className,
  children,
  ...rest
}: FormFieldProps) {
  const { t } = useUiText();
  const base = useId();
  const controlId = givenControlId ?? `${base}-control`;
  const labelId = `${base}-label`;
  const descriptionId = `${base}-description`;
  const errorId = `${base}-error`;
  const hasDescription = description !== undefined && description.trim() !== "";
  const hasError = error !== undefined && error.trim() !== "";
  const describedBy = [hasDescription ? descriptionId : null, hasError ? errorId : null]
    .filter((id) => id !== null)
    .join(" ");

  return (
    <div {...rest} className={cn("flex flex-col gap-1.5", className)}>
      <Label id={labelId} htmlFor={controlId} disabled={disabled}>
        {label}
        {required ? (
          <>
            {" "}
            <span className="font-normal text-text-secondary">{t("common.required")}</span>
          </>
        ) : null}
      </Label>
      {children({
        id: controlId,
        "aria-labelledby": labelId,
        ...(describedBy === "" ? {} : { "aria-describedby": describedBy }),
        ...(hasError ? { "aria-invalid": true as const } : {}),
        ...(required ? { "aria-required": true as const } : {}),
        ...(disabled ? { disabled: true as const } : {}),
      })}
      {hasDescription ? (
        <p id={descriptionId} className="text-sm text-text-secondary">
          {description}
        </p>
      ) : null}
      {hasError ? <FormError id={errorId}>{error}</FormError> : null}
    </div>
  );
}

export type FormErrorProps = ComponentProps<"p">;

/**
 * The message of a field error: text with a drawing next to it, so the error never depends on color
 * alone (docs/ui-ux/03 section 5). It carries no live role: a field announces its error when it
 * takes focus, through `aria-describedby`, and the form announces the failed submit once, in its
 * summary.
 */
export function FormError({ className, children, ...rest }: FormErrorProps) {
  return (
    <p {...rest} className={cn("flex items-start gap-1.5 text-sm text-status-overdue-fg", className)}>
      <CircleAlert className="mt-0.5 size-4 shrink-0" />
      {children}
    </p>
  );
}
