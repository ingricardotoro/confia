import { Alert, Button, Checkbox, FormError, Label } from "@confia/ui";
import { useId } from "react";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { FieldGrid, OptionField, ReadOnlyField, TextField, type Option } from "../../../shared/resource-screen/components/fields.tsx";
import { ResourceFormDialog, type ResourceFormView } from "../../../shared/resource-screen/components/ResourceFormDialog.tsx";
import { DISCOUNT_CONTROL_IDS, type DiscountField, type DiscountValues } from "../discount-form.ts";
import { DISCOUNT_KINDS } from "../discounts-search.ts";
import { conceptIdsOf, joinConceptIds } from "../scholarship-form.ts";
import { BENEFIT_TYPES } from "../scholarships-search.ts";

export interface DiscountFormProps {
  readonly mode: "create" | "edit";
  readonly values: DiscountValues;
  /** Already translated. */
  readonly fieldErrors: Readonly<Partial<Record<DiscountField, string>>>;
  readonly form: ResourceFormView;
  /** The payment concepts a discount can cover: the active ones, and the ones the discount already covers. */
  readonly conceptOptions: readonly Option[];
  /** Set when the concepts failed to load. */
  readonly conceptLookupFailure?: { readonly onRetry: () => void } | undefined;
  /** The concepts loaded and none is active: a discount has nothing to cover yet. */
  readonly noConcepts: boolean;
  /** An approved assignment or an issued charge uses the discount: the type, the value and the stackable mark cannot change, and the scope can only shrink. */
  readonly inUse: boolean;
  readonly onChange: (field: DiscountField, value: string) => void;
  readonly onBlur: (field: DiscountField) => void;
  readonly onClose: () => void;
}

/**
 * The form of a discount: a code and a class (both fixed once it exists), a name, the type of benefit and its value (a percentage or a fixed amount, one
 * or the other), the validity (both days included), whether it stacks with the other benefits and the payment concepts it covers. While the discount is in
 * use the type and the value are locked and the scope can only be reduced, because an issued charge must always be explained with the discount it had.
 */
export function DiscountForm({
  mode,
  values,
  fieldErrors,
  form,
  conceptOptions,
  conceptLookupFailure,
  noConcepts,
  inUse,
  onChange,
  onBlur,
  onClose,
}: DiscountFormProps) {
  const { t } = useAdminText();
  const conceptsHelpId = useId();
  const conceptsErrorId = useId();
  const stackableHelpId = useId();
  const text = (field: DiscountField) => ({
    controlId: DISCOUNT_CONTROL_IDS[field],
    value: values[field],
    error: fieldErrors[field],
    onChange: (value: string) => {
      onChange(field, value);
    },
    onBlur: () => {
      onBlur(field);
    },
  });
  const chosen = new Set(conceptIdsOf(values.conceptIds));
  const toggle = (id: string, checked: boolean) => {
    const next = new Set(chosen);
    if (checked) next.add(id);
    else next.delete(id);
    // The order is the one of the options, so the same choice is always the same text.
    onChange("conceptIds", joinConceptIds(conceptOptions.map((option) => option.value).filter((value) => next.has(value))));
    onBlur("conceptIds");
  };
  const kindLabel = (kind: string) => t(`scholarships.discounts.kinds.${kind === "agreement" ? "agreement" : "campaign"}`);

  return (
    <ResourceFormDialog
      title={t(mode === "create" ? "scholarships.discounts.form.createTitle" : "scholarships.discounts.form.editTitle")}
      description={t(mode === "create" ? "scholarships.discounts.form.createDescription" : "scholarships.discounts.form.editDescription")}
      submitLabel={t("resource.save")}
      form={form}
      onClose={onClose}
    >
      <FieldGrid>
        {mode === "create" ? (
          <TextField label={t("scholarships.discounts.form.code")} required description={t("scholarships.discounts.form.codeHelp")} {...text("code")} />
        ) : (
          <ReadOnlyField label={t("scholarships.discounts.form.code")} controlId={DISCOUNT_CONTROL_IDS.code} value={values.code} />
        )}
        <TextField label={t("scholarships.discounts.form.name")} required {...text("name")} />
      </FieldGrid>
      {mode === "create" ? (
        <OptionField
          label={t("scholarships.discounts.form.kind")}
          required
          description={t("scholarships.discounts.form.kindHelp")}
          options={DISCOUNT_KINDS.map((kind) => ({ value: kind, label: kindLabel(kind) }))}
          placeholder={t("resource.chooseOption")}
          {...text("kind")}
        />
      ) : (
        <ReadOnlyField label={t("scholarships.discounts.form.kind")} controlId={DISCOUNT_CONTROL_IDS.kind} value={kindLabel(values.kind)} />
      )}
      {values.kind === "campaign" ? (
        <Alert variant="info">
          <p>{t("scholarships.discounts.form.campaignApprovalNote")}</p>
        </Alert>
      ) : null}
      {inUse ? (
        <Alert variant="info">
          <p>{t("scholarships.discounts.form.inUse")}</p>
        </Alert>
      ) : null}
      {inUse ? (
        <FieldGrid>
          <ReadOnlyField
            label={t("scholarships.form.benefitType")}
            controlId={DISCOUNT_CONTROL_IDS.benefitType}
            value={t(`scholarships.benefitTypes.${values.benefitType === "FIXED_AMOUNT" ? "FIXED_AMOUNT" : "PERCENTAGE"}`)}
          />
          <ReadOnlyField
            label={t(values.benefitType === "FIXED_AMOUNT" ? "scholarships.form.fixedAmount" : "scholarships.form.percent")}
            controlId={values.benefitType === "FIXED_AMOUNT" ? DISCOUNT_CONTROL_IDS.fixedAmount : DISCOUNT_CONTROL_IDS.percent}
            value={values.benefitType === "FIXED_AMOUNT" ? values.fixedAmount : values.percent}
          />
        </FieldGrid>
      ) : (
        <FieldGrid>
          <OptionField
            label={t("scholarships.form.benefitType")}
            required
            options={BENEFIT_TYPES.map((type) => ({ value: type, label: t(`scholarships.benefitTypes.${type}`) }))}
            placeholder={t("resource.chooseOption")}
            {...text("benefitType")}
          />
          {values.benefitType === "FIXED_AMOUNT" ? (
            <TextField label={t("scholarships.form.fixedAmount")} required kind="numeric" description={t("scholarships.form.fixedAmountHelp")} {...text("fixedAmount")} />
          ) : values.benefitType === "PERCENTAGE" ? (
            <TextField label={t("scholarships.form.percent")} required kind="numeric" description={t("scholarships.form.percentHelp")} {...text("percent")} />
          ) : null}
        </FieldGrid>
      )}
      <FieldGrid>
        <TextField label={t("scholarships.discounts.form.validFrom")} required kind="date" {...text("validFrom")} />
        <TextField label={t("scholarships.discounts.form.validTo")} required kind="date" description={t("scholarships.discounts.form.validToHelp")} {...text("validTo")} />
      </FieldGrid>
      {inUse ? (
        // In use, the mark is locked like the type and the value (S8): it is shown, not offered.
        <ReadOnlyField
          label={t("scholarships.discounts.form.stackable")}
          controlId={DISCOUNT_CONTROL_IDS.stackable}
          value={t(values.stackable === "true" ? "scholarships.discounts.stackableYes" : "scholarships.discounts.stackableNo")}
        />
      ) : (
        <div className="flex flex-col gap-1">
          <div className="flex items-center gap-2">
            <Checkbox
              id={DISCOUNT_CONTROL_IDS.stackable}
              aria-describedby={stackableHelpId}
              checked={values.stackable === "true"}
              onCheckedChange={(next) => {
                onChange("stackable", next === true ? "true" : "false");
              }}
            />
            <Label htmlFor={DISCOUNT_CONTROL_IDS.stackable}>{t("scholarships.discounts.form.stackable")}</Label>
          </div>
          <p id={stackableHelpId} className="text-sm text-text-secondary">
            {t("scholarships.discounts.form.stackableHelp")}
          </p>
        </div>
      )}
      {conceptLookupFailure === undefined ? null : (
        <Alert variant="warning">
          <p>{t("resource.optionsFailed")}</p>
          <Button variant="secondary" size="xs" className="mt-2" onClick={conceptLookupFailure.onRetry}>
            {t("resource.retryOptions")}
          </Button>
        </Alert>
      )}
      {noConcepts ? (
        <Alert variant="info">
          <p>{t("scholarships.form.noConcepts")}</p>
        </Alert>
      ) : null}
      <fieldset
        className="flex flex-col gap-2 rounded-md border border-border-subtle p-3"
        aria-describedby={fieldErrors.conceptIds === undefined ? conceptsHelpId : `${conceptsHelpId} ${conceptsErrorId}`}
      >
        <legend className="px-1 text-sm font-medium text-text-primary">{t("scholarships.discounts.form.concepts")}</legend>
        <p id={conceptsHelpId} className="text-sm text-text-secondary">
          {t("scholarships.discounts.form.conceptsHelp")}
        </p>
        {conceptOptions.map((option, index) => {
          const id = index === 0 ? DISCOUNT_CONTROL_IDS.conceptIds : `${DISCOUNT_CONTROL_IDS.conceptIds}-${String(index)}`;
          return (
            <div key={option.value} className="flex items-center gap-2">
              <Checkbox
                id={id}
                checked={chosen.has(option.value)}
                onCheckedChange={(next) => {
                  toggle(option.value, next === true);
                }}
              />
              <Label htmlFor={id}>{option.label}</Label>
            </div>
          );
        })}
        {fieldErrors.conceptIds === undefined ? null : <FormError id={conceptsErrorId}>{fieldErrors.conceptIds}</FormError>}
      </fieldset>
    </ResourceFormDialog>
  );
}
