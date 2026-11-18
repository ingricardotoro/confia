import { AddChargePlanItemRequest, type AddChargePlanItemRequest as AddBody, type ChargePlanItemResponseOutput } from "@confia/contracts/admin";
import { reasonsFromSchema, requiredReasons } from "../../shared/forms/schema-reasons.ts";
import type { FieldReason } from "../../shared/forms/use-access-form.ts";

export type ChargePlanItem = ChargePlanItemResponseOutput;
export type ChargePlanItemField = "paymentConceptId" | "periodsCount" | "firstPeriodOn";

export const CHARGE_PLAN_ITEM_CONTROL_IDS: Readonly<Record<ChargePlanItemField, string>> = {
  paymentConceptId: "charge-plan-item-concept",
  periodsCount: "charge-plan-item-periods",
  firstPeriodOn: "charge-plan-item-first-period",
};

export type ChargePlanItemValues = Readonly<Record<ChargePlanItemField, string>>;

export const initialChargePlanItemValues = (): ChargePlanItemValues => ({ paymentConceptId: "", periodsCount: "", firstPeriodOn: "" });

/** The periods of an item are from 1 to 36; the server judges the same. */
export const PERIODS = { first: 1, last: 36 } as const;

/** The first and the last first day of a period the server accepts (ISO dates compare correctly as text). */
export const FIRST_PERIOD_WINDOW = { first: "2000-01-01", last: "2100-12-01" } as const;

const ISO_DAY = /^\d{4}-\d{2}-\d{2}$/;

/** The reason a count of periods typed as text is refused, judged on its characters and then on its value. */
export function periodsReason(raw: string): FieldReason | undefined {
  const text = raw.trim();
  if (text === "") return "required";
  if (!/^\d{1,3}$/.test(text)) return "periods-range";
  const count = Number(text);
  return count >= PERIODS.first && count <= PERIODS.last ? undefined : "periods-range";
}

/**
 * The reason the first day of the first period is refused: it is a date, inside the window the server takes, and the first
 * day of a month. Judged on the text of the date, so no time zone moves the day.
 */
export function firstPeriodReason(raw: string): FieldReason | undefined {
  if (raw === "") return "required";
  if (!ISO_DAY.test(raw)) return "format";
  if (raw < FIRST_PERIOD_WINDOW.first || raw > FIRST_PERIOD_WINDOW.last) return "date-range";
  return raw.endsWith("-01") ? undefined : "first-of-month";
}

/** The count as the request takes it: a whole number when the text is one, else the text, so the schema refuses it. */
const countOf = (raw: string): number | string => (/^\d{1,3}$/.test(raw.trim()) ? Number(raw.trim()) : raw);

/** The request of a new item. A count that is not a whole number is refused by `validateChargePlanItem` before anything is sent. */
export const addBodyOf = (values: ChargePlanItemValues): AddBody => ({
  paymentConceptId: values.paymentConceptId,
  periodsCount: countOf(values.periodsCount) as number,
  firstPeriodOn: values.firstPeriodOn,
});

/** The reasons the form is refused before sending: the generated request for the concept, then the count and the day judged here. */
export function validateChargePlanItem(values: ChargePlanItemValues): Partial<Record<ChargePlanItemField, FieldReason>> {
  const reasons: Partial<Record<ChargePlanItemField, FieldReason>> = {
    ...reasonsFromSchema(AddChargePlanItemRequest, addBodyOf(values), ["paymentConceptId"]),
    ...requiredReasons(values, ["paymentConceptId"]),
  };
  const periods = periodsReason(values.periodsCount);
  if (periods !== undefined) reasons.periodsCount = periods;
  const first = firstPeriodReason(values.firstPeriodOn);
  if (first !== undefined) reasons.firstPeriodOn = first;
  return reasons;
}
