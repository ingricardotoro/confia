import { Alert, Button, Checkbox, Label } from "@confia/ui";
import { useId } from "react";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { FieldGrid, OptionField, ReadOnlyField, TextField, type Option } from "../../../shared/resource-screen/components/fields.tsx";
import { ResourceFormDialog, type ResourceFormView } from "../../../shared/resource-screen/components/ResourceFormDialog.tsx";
import { PAYMENT_CATEGORIES } from "../catalog-search.ts";
import { CATEGORY_LABEL_KEYS } from "../category-options.ts";
import { PAYMENT_CONCEPT_CONTROL_IDS, needsTaxRate, type PaymentConceptField, type PaymentConceptValues } from "../payment-concept-form.ts";

export interface PaymentConceptFormProps {
  readonly mode: "create" | "edit";
  readonly values: PaymentConceptValues;
  /** Already translated. */
  readonly fieldErrors: Readonly<Partial<Record<PaymentConceptField, string>>>;
  readonly form: ResourceFormView;
  /** The tax rates a taxable concept can name: the active ones, and the one the concept already holds. */
  readonly taxRateOptions: readonly Option[];
  /** Set when the tax rates failed to load. */
  readonly taxRateLookupFailure?: { readonly onRetry: () => void } | undefined;
  /** The rates loaded and none of them is active: a taxable concept has nothing to choose yet. */
  readonly noActiveTaxRates: boolean;
  readonly onChange: (field: PaymentConceptField, value: string) => void;
  readonly onBlur: (field: PaymentConceptField) => void;
  readonly onClose: () => void;
}

interface CheckFieldProps {
  readonly controlId: string;
  readonly label: string;
  readonly help: string;
  readonly checked: boolean;
  readonly onChange: (checked: boolean) => void;
}

/** A yes or no field: a checkbox with its label and a help text that the control is described by. */
function CheckField({ controlId, label, help, checked, onChange }: CheckFieldProps) {
  const helpId = useId();
  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-center gap-2">
        <Checkbox
          id={controlId}
          aria-describedby={helpId}
          checked={checked}
          onCheckedChange={(next) => {
            onChange(next === true);
          }}
        />
        <Label htmlFor={controlId}>{label}</Label>
      </div>
      <p id={helpId} className="text-sm text-text-secondary">
        {help}
      </p>
    </div>
  );
}

/**
 * The form of a payment concept: a code, a name, a category, whether it recurs and whether it is taxable (and, then, which
 * tax rate it takes) and, when editing, whether it is active. The code is fixed once the concept exists, because other
 * cuts refer to it. A concept that is not taxable takes no rate, so the field is not there.
 */
export function PaymentConceptForm({
  mode,
  values,
  fieldErrors,
  form,
  taxRateOptions,
  taxRateLookupFailure,
  noActiveTaxRates,
  onChange,
  onBlur,
  onClose,
}: PaymentConceptFormProps) {
  const { t } = useAdminText();
  const text = (field: PaymentConceptField) => ({
    controlId: PAYMENT_CONCEPT_CONTROL_IDS[field],
    value: values[field],
    error: fieldErrors[field],
    onChange: (value: string) => {
      onChange(field, value);
    },
    onBlur: () => {
      onBlur(field);
    },
  });
  const check = (field: "isRecurring" | "isTaxable" | "isActive", label: string, help: string) => (
    <CheckField
      controlId={PAYMENT_CONCEPT_CONTROL_IDS[field]}
      label={label}
      help={help}
      checked={values[field] === "true"}
      onChange={(checked) => {
        onChange(field, checked ? "true" : "false");
      }}
    />
  );

  return (
    <ResourceFormDialog
      title={t(mode === "create" ? "catalog.paymentConcepts.createTitle" : "catalog.paymentConcepts.editTitle")}
      description={t(mode === "create" ? "catalog.paymentConcepts.createDescription" : "catalog.paymentConcepts.editDescription")}
      submitLabel={t("resource.save")}
      form={form}
      onClose={onClose}
    >
      <FieldGrid>
      {mode === "create" ? (
        <TextField label={t("catalog.paymentConcepts.fields.code")} required description={t("catalog.paymentConcepts.codeHelp")} {...text("code")} />
      ) : (
        <ReadOnlyField label={t("catalog.paymentConcepts.fields.code")} controlId={PAYMENT_CONCEPT_CONTROL_IDS.code} value={values.code} />
      )}
      <TextField label={t("catalog.paymentConcepts.fields.name")} required {...text("name")} />
      </FieldGrid>
      <OptionField
        label={t("catalog.paymentConcepts.fields.category")}
        required
        options={PAYMENT_CATEGORIES.map((category) => ({ value: category, label: t(CATEGORY_LABEL_KEYS[category]) }))}
        placeholder={t("resource.chooseOption")}
        {...text("category")}
      />
      <FieldGrid>
      {check("isRecurring", t("catalog.paymentConcepts.fields.isRecurring"), t("catalog.paymentConcepts.recurringHelp"))}
      {check("isTaxable", t("catalog.paymentConcepts.fields.isTaxable"), t("catalog.paymentConcepts.taxableHelp"))}
      </FieldGrid>
      {needsTaxRate(values.isTaxable) ? (
        <>
          {taxRateLookupFailure === undefined ? null : (
            <Alert variant="warning">
              <p>{t("resource.optionsFailed")}</p>
              <Button variant="secondary" size="xs" className="mt-2" onClick={taxRateLookupFailure.onRetry}>
                {t("resource.retryOptions")}
              </Button>
            </Alert>
          )}
          {noActiveTaxRates ? (
            <Alert variant="info">
              <p>{t("catalog.paymentConcepts.noActiveTaxRates")}</p>
            </Alert>
          ) : null}
          <OptionField
            label={t("catalog.paymentConcepts.fields.taxRate")}
            required
            description={t("catalog.paymentConcepts.taxRateHelp")}
            options={taxRateOptions}
            placeholder={t("resource.chooseOption")}
            {...text("taxRateId")}
          />
        </>
      ) : null}
      {mode === "edit" ? check("isActive", t("catalog.paymentConcepts.fields.isActive"), t("catalog.paymentConcepts.activeHelp")) : null}
    </ResourceFormDialog>
  );
}
