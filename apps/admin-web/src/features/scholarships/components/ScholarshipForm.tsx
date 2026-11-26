import { Alert, Button, Checkbox, FormError, Label } from "@confia/ui";
import { useId } from "react";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { FieldGrid, OptionField, ReadOnlyField, TextField, type Option } from "../../../shared/resource-screen/components/fields.tsx";
import { ResourceFormDialog, type ResourceFormView } from "../../../shared/resource-screen/components/ResourceFormDialog.tsx";
import { BENEFIT_TYPES } from "../scholarships-search.ts";
import { conceptIdsOf, joinConceptIds, SCHOLARSHIP_CONTROL_IDS, type ScholarshipField, type ScholarshipValues } from "../scholarship-form.ts";

export interface ScholarshipFormProps {
  readonly mode: "create" | "edit";
  readonly values: ScholarshipValues;
  /** Already translated. */
  readonly fieldErrors: Readonly<Partial<Record<ScholarshipField, string>>>;
  readonly form: ResourceFormView;
  /** The payment concepts a scholarship can cover: the active ones, and the ones the scholarship already covers. */
  readonly conceptOptions: readonly Option[];
  /** Set when the concepts failed to load. */
  readonly conceptLookupFailure?: { readonly onRetry: () => void } | undefined;
  /** The concepts loaded and none is active: a scholarship has nothing to cover yet. */
  readonly noConcepts: boolean;
  /** An award was approved or revoked once: the type and the value of the benefit cannot change any more (create another scholarship). */
  readonly benefitLocked: boolean;
  readonly onChange: (field: ScholarshipField, value: string) => void;
  readonly onBlur: (field: ScholarshipField) => void;
  readonly onClose: () => void;
}

/**
 * The form of a scholarship: a code (fixed once it exists), a name, the type of benefit and its value (a percentage or a fixed amount, one or the
 * other), an optional cap per period, and the payment concepts it covers. The value of the benefit is locked when an award was approved, because a
 * charge already issued must always be explained with the scholarship it had.
 */
export function ScholarshipForm({
  mode,
  values,
  fieldErrors,
  form,
  conceptOptions,
  conceptLookupFailure,
  noConcepts,
  benefitLocked,
  onChange,
  onBlur,
  onClose,
}: ScholarshipFormProps) {
  const { t } = useAdminText();
  const conceptsHelpId = useId();
  const conceptsErrorId = useId();
  const text = (field: ScholarshipField) => ({
    controlId: SCHOLARSHIP_CONTROL_IDS[field],
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

  return (
    <ResourceFormDialog
      title={t(mode === "create" ? "scholarships.form.createTitle" : "scholarships.form.editTitle")}
      description={t(mode === "create" ? "scholarships.form.createDescription" : "scholarships.form.editDescription")}
      submitLabel={t("resource.save")}
      form={form}
      onClose={onClose}
    >
      <FieldGrid>
        {mode === "create" ? (
          <TextField label={t("scholarships.form.code")} required description={t("scholarships.form.codeHelp")} {...text("code")} />
        ) : (
          <ReadOnlyField label={t("scholarships.form.code")} controlId={SCHOLARSHIP_CONTROL_IDS.code} value={values.code} />
        )}
        <TextField label={t("scholarships.form.name")} required {...text("name")} />
      </FieldGrid>
      {benefitLocked ? (
        <Alert variant="info">
          <p>{t("scholarships.form.benefitLocked")}</p>
        </Alert>
      ) : null}
      {benefitLocked ? (
        <FieldGrid>
          <ReadOnlyField label={t("scholarships.form.benefitType")} controlId={SCHOLARSHIP_CONTROL_IDS.benefitType} value={t(`scholarships.benefitTypes.${values.benefitType === "FIXED_AMOUNT" ? "FIXED_AMOUNT" : "PERCENTAGE"}`)} />
          <ReadOnlyField
            label={t(values.benefitType === "FIXED_AMOUNT" ? "scholarships.form.fixedAmount" : "scholarships.form.percent")}
            controlId={values.benefitType === "FIXED_AMOUNT" ? SCHOLARSHIP_CONTROL_IDS.fixedAmount : SCHOLARSHIP_CONTROL_IDS.percent}
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
      <TextField label={t("scholarships.form.periodCap")} kind="numeric" description={t("scholarships.form.periodCapHelp")} {...text("periodCap")} />
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
        <legend className="px-1 text-sm font-medium text-text-primary">{t("scholarships.form.concepts")}</legend>
        <p id={conceptsHelpId} className="text-sm text-text-secondary">
          {t("scholarships.form.conceptsHelp")}
        </p>
        {conceptOptions.map((option, index) => {
          const id = index === 0 ? SCHOLARSHIP_CONTROL_IDS.conceptIds : `${SCHOLARSHIP_CONTROL_IDS.conceptIds}-${String(index)}`;
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
        {fieldErrors.conceptIds === undefined ? null : (
          <FormError id={conceptsErrorId}>{fieldErrors.conceptIds}</FormError>
        )}
      </fieldset>
    </ResourceFormDialog>
  );
}
