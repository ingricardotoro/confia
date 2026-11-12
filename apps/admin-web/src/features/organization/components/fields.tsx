import { Badge, Button, FormField, Input, Select } from "@confia/ui";
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

/** A value that is fixed once the record exists: shown and readable, not editable. */
export function ReadOnlyField({ label, controlId, value }: { readonly label: string; readonly controlId: string; readonly value: string }) {
  const { t } = useAdminText();
  return (
    <FormField label={label} controlId={controlId} description={t("organization.common.fixedNote")}>
      {(control) => <Input {...control} readOnly value={value} />}
    </FormField>
  );
}

/** Active or inactive, in text. */
export function ActiveBadge({ active }: { readonly active: boolean }) {
  const { t } = useAdminText();
  return <Badge variant={active ? "primary" : "outline"}>{active ? t("organization.common.active") : t("organization.common.inactive")}</Badge>;
}

export interface RowActionsProps {
  /** Already translated, for the name of the buttons. */
  readonly name: string;
  readonly active: boolean;
  readonly busy: boolean;
  readonly onEdit: () => void;
  readonly onToggle: () => void;
  /** More buttons of the row, after the two common ones. */
  readonly extra?: ReactNode;
}

/** The actions of a row: edit, and activate or deactivate. Each button says on which record it acts. */
export function RowActions({ name, active, busy, onEdit, onToggle, extra }: RowActionsProps) {
  const { t } = useAdminText();
  return (
    <div className="flex flex-wrap gap-2">
      <Button variant="secondary" size="sm" aria-label={t("organization.common.editFor", { name })} onClick={onEdit}>
        {t("organization.common.edit")}
      </Button>
      <Button
        variant="secondary"
        size="sm"
        loading={busy}
        aria-label={t(active ? "organization.common.deactivateFor" : "organization.common.activateFor", { name })}
        onClick={onToggle}
      >
        {t(active ? "organization.common.deactivate" : "organization.common.activate")}
      </Button>
      {extra}
    </div>
  );
}
