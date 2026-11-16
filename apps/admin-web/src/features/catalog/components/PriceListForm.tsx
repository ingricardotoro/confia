import { Alert, Button } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { OptionField, TextField, type Option } from "../../../shared/resource-screen/components/fields.tsx";
import { ResourceFormDialog, type ResourceFormView } from "../../../shared/resource-screen/components/ResourceFormDialog.tsx";
import { PRICE_LIST_SCOPES } from "../catalog-search.ts";
import { PRICE_LIST_CONTROL_IDS, needsReference, type PriceListField, type PriceListValues } from "../price-list-form.ts";

export interface PriceListFormProps {
  readonly values: PriceListValues;
  /** Already translated. */
  readonly fieldErrors: Readonly<Partial<Record<PriceListField, string>>>;
  readonly form: ResourceFormView;
  /** The choices for each reference: the years, the modalities and the grades of the institution. */
  readonly yearOptions: readonly Option[];
  readonly modalityOptions: readonly Option[];
  readonly gradeOptions: readonly Option[];
  /** Set when those lists failed to load. */
  readonly lookupFailure?: { readonly onRetry: () => void } | undefined;
  readonly onChange: (field: PriceListField, value: string) => void;
  readonly onBlur: (field: PriceListField) => void;
  readonly onClose: () => void;
}

/**
 * The form of a new price list: the year, the scope, the modality or the grade when the scope asks for one, and a
 * name. The year, the scope and the reference are fixed once the list exists, so there is no edit form.
 */
export function PriceListForm({
  values,
  fieldErrors,
  form,
  yearOptions,
  modalityOptions,
  gradeOptions,
  lookupFailure,
  onChange,
  onBlur,
  onClose,
}: PriceListFormProps) {
  const { t } = useAdminText();
  const bind = (field: PriceListField) => ({
    controlId: PRICE_LIST_CONTROL_IDS[field],
    value: values[field],
    error: fieldErrors[field],
    onChange: (value: string) => {
      onChange(field, value);
    },
    onBlur: () => {
      onBlur(field);
    },
  });
  const scopeOptions = PRICE_LIST_SCOPES.map((scope) => ({ value: scope, label: t(`catalog.priceLists.scopes.${scope}`) }));

  return (
    <ResourceFormDialog
      title={t("catalog.priceLists.createTitle")}
      description={t("catalog.priceLists.createDescription")}
      submitLabel={t("resource.save")}
      form={form}
      onClose={onClose}
    >
      {lookupFailure === undefined ? null : (
        <Alert variant="warning">
          <p>{t("resource.optionsFailed")}</p>
          <Button variant="secondary" size="sm" className="mt-2" onClick={lookupFailure.onRetry}>
            {t("resource.retryOptions")}
          </Button>
        </Alert>
      )}
      <OptionField
        label={t("catalog.priceLists.fields.academicYear")}
        required
        options={yearOptions}
        placeholder={t("resource.chooseOption")}
        {...bind("academicYearId")}
      />
      <OptionField label={t("catalog.priceLists.fields.scope")} required options={scopeOptions} {...bind("scope")} />
      {needsReference(values.scope) ? (
        <OptionField
          // A new control for each scope, so the choice made for one is never shown for the other.
          key={values.scope}
          label={t(values.scope === "MODALITY" ? "catalog.priceLists.fields.modality" : "catalog.priceLists.fields.grade")}
          required
          options={values.scope === "MODALITY" ? modalityOptions : gradeOptions}
          placeholder={t("resource.chooseOption")}
          {...bind("scopeRefId")}
        />
      ) : null}
      <TextField label={t("catalog.priceLists.fields.name")} required description={t("catalog.priceLists.nameHelp")} {...bind("name")} />
    </ResourceFormDialog>
  );
}
