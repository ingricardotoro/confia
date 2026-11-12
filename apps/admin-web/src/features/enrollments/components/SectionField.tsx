import { FormField, Select } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import type { OptionGroup } from "../enrollment-model.ts";

export interface SectionFieldProps {
  readonly label: string;
  readonly controlId: string;
  readonly value: string;
  /** Already translated: one group for each "Modalidad · Grado". */
  readonly groups: readonly OptionGroup[];
  readonly onChange: (value: string) => void;
  readonly onBlur: () => void;
  readonly error?: string | undefined;
  readonly description?: string;
  readonly required?: boolean;
}

/** A labelled native select of sections, grouped by modality and grade, as the browser draws its own groups. */
export function SectionField({ label, controlId, value, groups, onChange, onBlur, error, description, required = false }: SectionFieldProps) {
  const { t } = useAdminText();
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
          onBlur={onBlur}
        >
          <option value="">{t("organization.common.chooseOption")}</option>
          {groups.map((group) => (
            <optgroup key={group.label} label={group.label}>
              {group.options.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </optgroup>
          ))}
        </Select>
      )}
    </FormField>
  );
}
