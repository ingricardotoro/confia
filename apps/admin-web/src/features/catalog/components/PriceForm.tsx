import { Alert, Button } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { FieldGrid, OptionField, TextField, type Option } from "../../../shared/resource-screen/components/fields.tsx";
import { ResourceFormDialog, type ResourceFormView } from "../../../shared/resource-screen/components/ResourceFormDialog.tsx";
import { CURRENCIES, PRICE_CONTROL_IDS, type PriceField, type PriceValues } from "../price-form.ts";

export interface PriceFormProps {
  readonly values: PriceValues;
  /** Already translated. */
  readonly fieldErrors: Readonly<Partial<Record<PriceField, string>>>;
  readonly form: ResourceFormView;
  /** The active concepts only: a price is never set for an inactive one. */
  readonly conceptOptions: readonly Option[];
  /** Set when the concepts failed to load. */
  readonly lookupFailure?: { readonly onRetry: () => void } | undefined;
  /** The concepts loaded and none of them is active: there is nothing to price yet. */
  readonly noActiveConcepts: boolean;
  readonly onChange: (field: PriceField, value: string) => void;
  readonly onBlur: (field: PriceField) => void;
  readonly onClose: () => void;
}

/**
 * The form of a new price or a new validity: the concept, the amount and its currency, and the dates the price holds.
 * The amount is a text the server judges and rounds: nothing here turns it into a number. A saved price is never
 * edited, so this is the only form of a price.
 */
export function PriceForm({ values, fieldErrors, form, conceptOptions, lookupFailure, noActiveConcepts, onChange, onBlur, onClose }: PriceFormProps) {
  const { t } = useAdminText();
  const bind = (field: PriceField) => ({
    controlId: PRICE_CONTROL_IDS[field],
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
      title={t("catalog.priceLists.addPrice.title")}
      description={t("catalog.priceLists.addPrice.description")}
      submitLabel={t("catalog.priceLists.addPrice.submit")}
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
      {noActiveConcepts ? (
        <Alert variant="info">
          <p>{t("catalog.priceLists.addPrice.noActiveConcepts")}</p>
        </Alert>
      ) : null}
      <OptionField
        label={t("catalog.priceLists.addPrice.fields.concept")}
        required
        options={conceptOptions}
        placeholder={t("resource.chooseOption")}
        {...bind("paymentConceptId")}
      />
      <FieldGrid>
      <TextField
        label={t("catalog.priceLists.addPrice.fields.amount")}
        required
        kind="numeric"
        description={t("catalog.priceLists.addPrice.amountHelp")}
        {...bind("amount")}
      />
      <OptionField
        label={t("catalog.priceLists.addPrice.fields.currency")}
        required
        options={CURRENCIES.map((currency) => ({ value: currency, label: t(`catalog.priceLists.currencies.${currency}`) }))}
        {...bind("currency")}
      />
      </FieldGrid>
      <FieldGrid>
      <TextField label={t("catalog.priceLists.addPrice.fields.validFrom")} required kind="date" {...bind("validFrom")} />
      <TextField
        label={t("catalog.priceLists.addPrice.fields.validTo")}
        kind="date"
        description={t("catalog.priceLists.addPrice.validToHelp")}
        {...bind("validTo")}
      />
      </FieldGrid>
    </ResourceFormDialog>
  );
}
