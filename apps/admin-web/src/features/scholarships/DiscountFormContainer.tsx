import { useAdminText } from "../../i18n/use-admin-text.ts";
import { useConceptLookup } from "../../shared/catalog-lookups/use-concept-lookup.ts";
import { useAccessForm } from "../../shared/forms/use-access-form.ts";
import type { Option } from "../../shared/resource-screen/components/fields.tsx";
import { failureOf } from "../../shared/resource-screen/resource-failure.ts";
import { fieldSpecs } from "../../shared/resource-screen/resource-form.ts";
import { DiscountForm } from "./components/DiscountForm.tsx";
import {
  DISCOUNT_CONTROL_IDS,
  discountSaveOf,
  initialDiscountValues,
  validateDiscount,
  type Discount,
  type DiscountField,
  type DiscountSave,
} from "./discount-form.ts";

export interface DiscountFormContainerProps {
  /** Absent when the discount is new. */
  readonly row?: Discount;
  /** Rejects with the failure of the request; resolves when the discount is saved. */
  readonly save: (save: DiscountSave) => Promise<void>;
  readonly onClose: () => void;
}

/** The behavior of the form of a discount: validate before sending, send once, and turn a failure into what the form shows. */
export function DiscountFormContainer({ row, save, onClose }: DiscountFormContainerProps) {
  const { t } = useAdminText();
  const concepts = useConceptLookup();
  const form = useAccessForm<DiscountField>({
    fields: fieldSpecs(DISCOUNT_CONTROL_IDS, initialDiscountValues(row), ["code", "name"]),
    validate: validateDiscount(row),
    submit: async (values) => {
      try {
        await save(discountSaveOf(row, values));
        return undefined;
      } catch (error) {
        return failureOf(error);
      }
    },
    waitingKey: "resource.saving",
  });

  // The active concepts, and the ones the discount already covers: one may have been deactivated since, and the list must still show what the
  // discount has.
  const covered = new Set(row?.concepts.map((concept) => concept.id));
  const conceptOptions: Option[] = concepts.rows
    .filter((concept) => concept.isActive || covered.has(concept.id))
    .map((concept) => ({ value: concept.id, label: t("scholarships.form.conceptOption", { code: concept.code, name: concept.name }) }));

  return (
    <DiscountForm
      mode={row === undefined ? "create" : "edit"}
      values={form.values}
      fieldErrors={form.fieldErrors}
      form={form}
      conceptOptions={conceptOptions}
      conceptLookupFailure={concepts.failed ? { onRetry: concepts.retry } : undefined}
      noConcepts={!concepts.loading && !concepts.failed && conceptOptions.length === 0}
      inUse={row?.inUse ?? false}
      onChange={form.onChange}
      onBlur={form.onBlur}
      onClose={onClose}
    />
  );
}
