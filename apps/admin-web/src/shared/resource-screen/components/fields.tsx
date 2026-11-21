import { Badge, Button, FormField, Input, Select, Textarea } from "@confia/ui";
import type { ReactNode } from "react";
import { useAdminText } from "../../../i18n/use-admin-text.ts";

export interface Option {
  readonly value: string;
  /** Already translated. */
  readonly label: string;
}

export interface OptionFieldProps {
  readonly label: string;
  readonly controlId: string;
  readonly value: string;
  readonly options: readonly Option[];
  /** The first option, with an empty value: "All" in a filter, "Choose" in a form. Absent when every option is a real value. */
  readonly placeholder?: string;
  readonly onChange: (value: string) => void;
  readonly onBlur?: () => void;
  readonly error?: string | undefined;
  readonly description?: string;
  readonly required?: boolean;
  readonly disabled?: boolean;
}

/** A labelled native select, for a form or for a filter. */
export function OptionField({
  label,
  controlId,
  value,
  options,
  placeholder,
  onChange,
  onBlur,
  error,
  description,
  required = false,
  disabled = false,
}: OptionFieldProps) {
  return (
    <FormField
      label={label}
      controlId={controlId}
      required={required}
      {...(error === undefined ? {} : { error })}
      {...(description === undefined ? {} : { description })}
    >
      {(control) => (
        <Select
          {...control}
          value={value}
          disabled={disabled}
          onChange={(event) => {
            onChange(event.target.value);
          }}
          {...(onBlur === undefined ? {} : { onBlur })}
        >
          {placeholder === undefined ? null : <option value="">{placeholder}</option>}
          {options.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </Select>
      )}
    </FormField>
  );
}

export interface TextFieldProps {
  readonly label: string;
  readonly controlId: string;
  readonly value: string;
  readonly onChange: (value: string) => void;
  readonly onBlur: () => void;
  readonly error?: string | undefined;
  readonly description?: string;
  readonly required?: boolean;
  /** The field cannot be edited now (it is not sent as a change either). */
  readonly disabled?: boolean;
  /** `date` is the native date input; `numeric` is text with the numeric keyboard, so the form words its own error. */
  readonly kind?: "text" | "date" | "numeric";
  /** Personal data: the browser must not spell-check, autocorrect or autofill it (those can send it to other services). */
  readonly sensitive?: boolean;
}

export function TextField({
  label,
  controlId,
  value,
  onChange,
  onBlur,
  error,
  description,
  required = false,
  disabled = false,
  kind = "text",
  sensitive = false,
}: TextFieldProps) {
  return (
    <FormField
      label={label}
      controlId={controlId}
      required={required}
      disabled={disabled}
      {...(error === undefined ? {} : { error })}
      {...(description === undefined ? {} : { description })}
    >
      {(control) => (
        <Input
          {...control}
          type={kind === "date" ? "date" : "text"}
          {...(kind === "numeric" ? { inputMode: "numeric" as const } : {})}
          autoComplete="off"
          {...(sensitive ? { spellCheck: false, autoCorrect: "off" } : {})}
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

export interface TextAreaFieldProps {
  readonly label: string;
  readonly controlId: string;
  readonly value: string;
  readonly onChange: (value: string) => void;
  readonly onBlur: () => void;
  readonly error?: string | undefined;
  readonly description?: string;
  readonly required?: boolean;
  readonly rows?: number;
  /** The most the server takes, so the control stops there. */
  readonly maxLength?: number;
}

/** A labelled text area for a free text such as a reason or a note. The text is free: the browser must not spell-check or autofill it, because it may hold something personal. */
export function TextAreaField({ label, controlId, value, onChange, onBlur, error, description, required = false, rows = 3, maxLength }: TextAreaFieldProps) {
  return (
    <FormField
      label={label}
      controlId={controlId}
      required={required}
      {...(error === undefined ? {} : { error })}
      {...(description === undefined ? {} : { description })}
    >
      {(control) => (
        <Textarea
          {...control}
          rows={rows}
          {...(maxLength === undefined ? {} : { maxLength })}
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

/** A value that is fixed once the record exists: shown and readable, not editable. */
export function ReadOnlyField({ label, controlId, value }: { readonly label: string; readonly controlId: string; readonly value: string }) {
  const { t } = useAdminText();
  return (
    <FormField label={label} controlId={controlId} description={t("resource.fixedNote")}>
      {(control) => <Input {...control} readOnly value={value} />}
    </FormField>
  );
}

/** Active or inactive, in text. */
export function ActiveBadge({ active }: { readonly active: boolean }) {
  const { t } = useAdminText();
  return <Badge variant={active ? "success" : "neutral"} dot={active ? "filled" : "hollow"}>{active ? t("resource.active") : t("resource.inactive")}</Badge>;
}

export interface RowActionsProps {
  /** Already translated, for the name of the buttons. */
  readonly name: string;
  readonly active: boolean;
  readonly busy: boolean;
  /** Absent: the edit button is not drawn. */
  readonly onEdit?: (() => void) | undefined;
  /** Absent: the activate or deactivate button is not drawn. */
  readonly onToggle?: (() => void) | undefined;
  /** More buttons of the row, after the two common ones. */
  readonly extra?: ReactNode;
}

/** The actions of a row: edit, and activate or deactivate. Each button says on which record it acts. */
export function RowActions({ name, active, busy, onEdit, onToggle, extra }: RowActionsProps) {
  const { t } = useAdminText();
  if (onEdit === undefined && onToggle === undefined && extra === undefined) return null;
  return (
    <div className="flex flex-wrap gap-1.5">
      {onEdit === undefined ? null : (
        <Button variant="secondary" size="xs" aria-label={t("resource.editFor", { name })} onClick={onEdit}>
          {t("resource.edit")}
        </Button>
      )}
      {onToggle === undefined ? null : (
        <Button
          variant={active ? "destructive-soft" : "success-soft"}
          size="xs"
          loading={busy}
          aria-label={t(active ? "resource.deactivateFor" : "resource.activateFor", { name })}
          onClick={onToggle}
        >
          {t(active ? "resource.deactivate" : "resource.activate")}
        </Button>
      )}
      {extra}
    </div>
  );
}

/**
 * Lays the short fields of a form out in two columns from the small breakpoint up (dates, amount and currency, code and
 * name). A field that needs the whole row goes in a `FullRow`.
 */
export function FieldGrid({ children }: { readonly children: ReactNode }) {
  return <div className="grid grid-cols-1 gap-x-3 gap-y-3 sm:grid-cols-2">{children}</div>;
}

/** A child of a `FieldGrid` that spans both columns. */
export function FullRow({ children }: { readonly children: ReactNode }) {
  return <div className="sm:col-span-2">{children}</div>;
}
