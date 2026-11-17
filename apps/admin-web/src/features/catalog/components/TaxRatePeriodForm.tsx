import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { FieldGrid, TextField } from "../../../shared/resource-screen/components/fields.tsx";
import { ResourceFormDialog, type ResourceFormView } from "../../../shared/resource-screen/components/ResourceFormDialog.tsx";
import { TAX_RATE_PERIOD_CONTROL_IDS, type TaxRatePeriodField, type TaxRatePeriodValues } from "../tax-rate-period-form.ts";

export interface TaxRatePeriodFormProps {
  readonly values: TaxRatePeriodValues;
  /** Already translated. */
  readonly fieldErrors: Readonly<Partial<Record<TaxRatePeriodField, string>>>;
  readonly form: ResourceFormView;
  readonly onChange: (field: TaxRatePeriodField, value: string) => void;
  readonly onBlur: (field: TaxRatePeriodField) => void;
  readonly onClose: () => void;
}

/**
 * The form of a new validity: the percentage and the dates it holds. The percentage is a text the server judges and
 * answers at four decimals: nothing here turns it into a number. A saved validity is never edited, so this is the only
 * form of one.
 */
export function TaxRatePeriodForm({ values, fieldErrors, form, onChange, onBlur, onClose }: TaxRatePeriodFormProps) {
  const { t } = useAdminText();
  const bind = (field: TaxRatePeriodField) => ({
    controlId: TAX_RATE_PERIOD_CONTROL_IDS[field],
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
      title={t("catalog.taxRates.addPeriod.title")}
      description={t("catalog.taxRates.addPeriod.description")}
      submitLabel={t("catalog.taxRates.addPeriod.submit")}
      form={form}
      onClose={onClose}
    >
      <TextField
        label={t("catalog.taxRates.addPeriod.fields.ratePercent")}
        required
        kind="numeric"
        description={t("catalog.taxRates.addPeriod.rateHelp")}
        {...bind("ratePercent")}
      />
      <FieldGrid>
      <TextField label={t("catalog.taxRates.addPeriod.fields.validFrom")} required kind="date" {...bind("validFrom")} />
      <TextField
        label={t("catalog.taxRates.addPeriod.fields.validTo")}
        kind="date"
        description={t("catalog.taxRates.addPeriod.validToHelp")}
        {...bind("validTo")}
      />
      </FieldGrid>
    </ResourceFormDialog>
  );
}
