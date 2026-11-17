import { Alert, Button } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { FieldGrid, OptionField, TextField, type Option } from "../../../shared/resource-screen/components/fields.tsx";
import { ResourceFormDialog, type ResourceFormView } from "../../../shared/resource-screen/components/ResourceFormDialog.tsx";
import { CHARGE_PLAN_ITEM_CONTROL_IDS, type ChargePlanItemField, type ChargePlanItemValues } from "../charge-plan-item-form.ts";

export interface ChargePlanItemFormProps {
  readonly values: ChargePlanItemValues;
  /** Already translated. */
  readonly fieldErrors: Readonly<Partial<Record<ChargePlanItemField, string>>>;
  readonly form: ResourceFormView;
  /** The active concepts the plan does not have yet: a concept is never added twice, and an inactive one never. */
  readonly conceptOptions: readonly Option[];
  /** Set when the concepts failed to load. */
  readonly lookupFailure?: { readonly onRetry: () => void } | undefined;
  /** The concepts loaded and none can be added. */
  readonly noConcepts: boolean;
  readonly onChange: (field: ChargePlanItemField, value: string) => void;
  readonly onBlur: (field: ChargePlanItemField) => void;
  readonly onClose: () => void;
}

/**
 * The form of a new item of a draft plan: the concept, how many periods it accrues and the first day of the first one,
 * which is always the first day of a month. The plan holds no price: the amount is resolved later, by date, from the price
 * lists. A saved item is never edited, only removed, so this is the only form of an item.
 */
export function ChargePlanItemForm({ values, fieldErrors, form, conceptOptions, lookupFailure, noConcepts, onChange, onBlur, onClose }: ChargePlanItemFormProps) {
  const { t } = useAdminText();
  const bind = (field: ChargePlanItemField) => ({
    controlId: CHARGE_PLAN_ITEM_CONTROL_IDS[field],
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
      title={t("charges.plans.addItem.title")}
      description={t("charges.plans.addItem.description")}
      submitLabel={t("charges.plans.addItem.submit")}
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
      {noConcepts ? (
        <Alert variant="info">
          <p>{t("charges.plans.addItem.noActiveConcepts")}</p>
        </Alert>
      ) : null}
      <OptionField
        label={t("charges.plans.addItem.fields.concept")}
        required
        options={conceptOptions}
        placeholder={t("resource.chooseOption")}
        {...bind("paymentConceptId")}
      />
      <FieldGrid>
        <TextField label={t("charges.plans.addItem.fields.periods")} required kind="numeric" description={t("charges.plans.addItem.periodsHelp")} {...bind("periodsCount")} />
        <TextField label={t("charges.plans.addItem.fields.firstPeriod")} required kind="date" description={t("charges.plans.addItem.firstPeriodHelp")} {...bind("firstPeriodOn")} />
      </FieldGrid>
    </ResourceFormDialog>
  );
}
