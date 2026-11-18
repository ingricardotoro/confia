import { Alert, Button } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { FieldGrid, FullRow, OptionField, TextField, type Option } from "../../../shared/resource-screen/components/fields.tsx";
import { ResourceFormDialog, type ResourceFormView } from "../../../shared/resource-screen/components/ResourceFormDialog.tsx";
import { CHARGE_PLAN_CONTROL_IDS, type ChargePlanField, type ChargePlanValues } from "../charge-plan-form.ts";
import { CHARGE_PLAN_FREQUENCIES, CHARGE_PLAN_SCOPES } from "../charges-search.ts";

export interface ChargePlanFormProps {
  readonly values: ChargePlanValues;
  /** Already translated. */
  readonly fieldErrors: Readonly<Partial<Record<ChargePlanField, string>>>;
  readonly form: ResourceFormView;
  /** The choices for each reference: the years, the modalities, the grades and the sections of the institution. */
  readonly yearOptions: readonly Option[];
  readonly modalityOptions: readonly Option[];
  readonly gradeOptions: readonly Option[];
  readonly sectionOptions: readonly Option[];
  /** Set when those lists failed to load. */
  readonly lookupFailure?: { readonly onRetry: () => void } | undefined;
  readonly onChange: (field: ChargePlanField, value: string) => void;
  readonly onBlur: (field: ChargePlanField) => void;
  readonly onClose: () => void;
}

/**
 * The form of a new plan: the name, the year, the scope with the modality, the grade or the section it points to, the
 * frequency and the due day. The reference follows the scope (a new control for each, so the choice made for one is never
 * shown for another). The year, the scope and the reference are fixed once the plan exists.
 */
export function ChargePlanForm({
  values,
  fieldErrors,
  form,
  yearOptions,
  modalityOptions,
  gradeOptions,
  sectionOptions,
  lookupFailure,
  onChange,
  onBlur,
  onClose,
}: ChargePlanFormProps) {
  const { t } = useAdminText();
  const bind = (field: ChargePlanField) => ({
    controlId: CHARGE_PLAN_CONTROL_IDS[field],
    value: values[field],
    error: fieldErrors[field],
    onChange: (value: string) => {
      onChange(field, value);
    },
    onBlur: () => {
      onBlur(field);
    },
  });
  const scopeOptions = CHARGE_PLAN_SCOPES.map((scope) => ({ value: scope, label: t(`charges.plans.scopes.${scope}`) }));
  const frequencyOptions = CHARGE_PLAN_FREQUENCIES.map((frequency) => ({ value: frequency, label: t(`charges.plans.frequencies.${frequency}`) }));
  const referenceOptions = values.targetScope === "MODALITY" ? modalityOptions : values.targetScope === "GRADE" ? gradeOptions : sectionOptions;
  const referenceLabel =
    values.targetScope === "MODALITY" ? "charges.plans.fields.modality" : values.targetScope === "GRADE" ? "charges.plans.fields.grade" : "charges.plans.fields.section";

  return (
    <ResourceFormDialog
      title={t("charges.plans.createTitle")}
      description={t("charges.plans.createDescription")}
      submitLabel={t("resource.save")}
      form={form}
      onClose={onClose}
    >
      {lookupFailure === undefined ? null : (
        <Alert variant="warning">
          <p>{t("resource.optionsFailed")}</p>
          <Button variant="secondary" size="xs" className="mt-2" onClick={lookupFailure.onRetry}>
            {t("resource.retryOptions")}
          </Button>
        </Alert>
      )}
      <TextField label={t("charges.plans.fields.name")} required description={t("charges.plans.nameHelp")} {...bind("name")} />
      <FieldGrid>
        <OptionField label={t("charges.plans.fields.academicYear")} required options={yearOptions} placeholder={t("resource.chooseOption")} {...bind("academicYearId")} />
        <OptionField label={t("charges.plans.fields.scope")} required options={scopeOptions} {...bind("targetScope")} />
        <FullRow>
          <OptionField
            // A new control for each scope, so the choice made for one is never shown for another.
            key={values.targetScope}
            label={t(referenceLabel)}
            required
            options={referenceOptions}
            placeholder={t("resource.chooseOption")}
            {...bind("targetRefId")}
          />
        </FullRow>
        <OptionField label={t("charges.plans.fields.frequency")} required options={frequencyOptions} {...bind("frequency")} />
        <TextField label={t("charges.plans.fields.dueDay")} required kind="numeric" description={t("charges.plans.dueDayHelp")} {...bind("dueDayOfMonth")} />
      </FieldGrid>
    </ResourceFormDialog>
  );
}
