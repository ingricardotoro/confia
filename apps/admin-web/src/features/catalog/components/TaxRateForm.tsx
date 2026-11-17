import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { FieldGrid, ReadOnlyField, TextField } from "../../../shared/resource-screen/components/fields.tsx";
import { ResourceFormDialog, type ResourceFormView } from "../../../shared/resource-screen/components/ResourceFormDialog.tsx";
import { TAX_RATE_CONTROL_IDS, type TaxRateField, type TaxRateValues } from "../tax-rate-form.ts";

export interface TaxRateFormProps {
  readonly mode: "create" | "edit";
  readonly values: TaxRateValues;
  /** Already translated. */
  readonly fieldErrors: Readonly<Partial<Record<TaxRateField, string>>>;
  readonly form: ResourceFormView;
  readonly onChange: (field: TaxRateField, value: string) => void;
  readonly onBlur: (field: TaxRateField) => void;
  readonly onClose: () => void;
}

/**
 * The form of a tax rate: a code and a name. The code is fixed once the rate exists, because the concepts point at it.
 * The percentage is not here: it belongs to a validity, which has its own form in the detail.
 */
export function TaxRateForm({ mode, values, fieldErrors, form, onChange, onBlur, onClose }: TaxRateFormProps) {
  const { t } = useAdminText();
  const text = (field: TaxRateField) => ({
    controlId: TAX_RATE_CONTROL_IDS[field],
    value: values[field],
    error: fieldErrors[field],
    onChange: (value: string) => {
      onChange(field, value);
    },
    onBlur: () => {
      onBlur(field);
    },
  });

  return (
    <ResourceFormDialog
      title={t(mode === "create" ? "catalog.taxRates.createTitle" : "catalog.taxRates.editTitle")}
      description={t(mode === "create" ? "catalog.taxRates.createDescription" : "catalog.taxRates.editDescription")}
      submitLabel={t("resource.save")}
      form={form}
      onClose={onClose}
    >
      <FieldGrid>
      {mode === "create" ? (
        <TextField label={t("catalog.taxRates.fields.code")} required description={t("catalog.taxRates.codeHelp")} {...text("code")} />
      ) : (
        <ReadOnlyField label={t("catalog.taxRates.fields.code")} controlId={TAX_RATE_CONTROL_IDS.code} value={values.code} />
      )}
      <TextField label={t("catalog.taxRates.fields.name")} required description={t("catalog.taxRates.nameHelp")} {...text("name")} />
      </FieldGrid>
    </ResourceFormDialog>
  );
}
