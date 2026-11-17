import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { FieldGrid, OptionField, TextField } from "../../../shared/resource-screen/components/fields.tsx";
import { ResourceFormDialog, type ResourceFormView } from "../../../shared/resource-screen/components/ResourceFormDialog.tsx";
import { CHARGE_PLAN_EDIT_CONTROL_IDS, type ChargePlanEditField, type ChargePlanEditValues } from "../charge-plan-form.ts";
import { CHARGE_PLAN_FREQUENCIES } from "../charges-search.ts";

export interface ChargePlanEditFormProps {
  readonly values: ChargePlanEditValues;
  /** Already translated. */
  readonly fieldErrors: Readonly<Partial<Record<ChargePlanEditField, string>>>;
  readonly form: ResourceFormView;
  /** An active plan is frozen: only its name changes, and the frequency and the due day are shown and cannot be edited. */
  readonly frozen: boolean;
  readonly onChange: (field: ChargePlanEditField, value: string) => void;
  readonly onBlur: (field: ChargePlanEditField) => void;
  readonly onClose: () => void;
}

/** The form of the edit of a plan: its name, its frequency and its due day. The year, the scope and the reference are not here: they never change. */
export function ChargePlanEditForm({ values, fieldErrors, form, frozen, onChange, onBlur, onClose }: ChargePlanEditFormProps) {
  const { t } = useAdminText();
  const bind = (field: ChargePlanEditField) => ({
    controlId: CHARGE_PLAN_EDIT_CONTROL_IDS[field],
    value: values[field],
    error: fieldErrors[field],
    onChange: (value: string) => {
      onChange(field, value);
    },
    onBlur: () => {
      onBlur(field);
    },
  });
  const frequencyOptions = CHARGE_PLAN_FREQUENCIES.map((frequency) => ({ value: frequency, label: t(`charges.plans.frequencies.${frequency}`) }));

  return (
    <ResourceFormDialog
      title={t("charges.plans.edit.title")}
      description={t(frozen ? "charges.plans.edit.descriptionActive" : "charges.plans.edit.description")}
      submitLabel={t("charges.plans.edit.submit")}
      form={form}
      onClose={onClose}
    >
      <TextField label={t("charges.plans.fields.name")} required description={t("charges.plans.nameHelp")} {...bind("name")} />
      <FieldGrid>
        <OptionField label={t("charges.plans.fields.frequency")} required disabled={frozen} options={frequencyOptions} {...bind("frequency")} />
        <TextField
          label={t("charges.plans.fields.dueDay")}
          required
          kind="numeric"
          disabled={frozen}
          description={t("charges.plans.dueDayHelp")}
          {...bind("dueDayOfMonth")}
        />
      </FieldGrid>
    </ResourceFormDialog>
  );
}
