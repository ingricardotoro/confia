import { AddPriceRequest, type AddPriceRequest as AddBody, type PriceListItemResponseOutput } from "@confia/contracts/admin";
import { reasonsFromSchema, requiredReasons } from "../../shared/forms/schema-reasons.ts";
import type { FieldReason } from "../../shared/forms/use-access-form.ts";

export type PriceItem = PriceListItemResponseOutput;
export type PriceField = "paymentConceptId" | "amount" | "currency" | "validFrom" | "validTo";

export const PRICE_CONTROL_IDS: Readonly<Record<PriceField, string>> = {
  paymentConceptId: "price-concept",
  amount: "price-amount",
  currency: "price-currency",
  validFrom: "price-valid-from",
  validTo: "price-valid-to",
};

/** The currencies the system operates with (the same set the server accepts). */
export const CURRENCIES = ["HNL", "USD"] as const;

export type PriceValues = Readonly<Record<PriceField, string>>;

export const initialPriceValues = (): PriceValues => ({ paymentConceptId: "", amount: "", currency: "HNL", validFrom: "", validTo: "" });

/** What the server takes as an amount: digits, up to ten before the point and four after. No sign, no separators. */
const AMOUNT_FORMAT = /^\d{1,10}(\.\d{1,4})?$/;

/**
 * The request of a new price. The amount stays the text that was typed: the browser neither parses nor rounds it, and
 * the server answers with it rounded. An empty end date is no end, so it is not sent.
 */
export const addBodyOf = (values: PriceValues): AddBody => ({
  paymentConceptId: values.paymentConceptId,
  price: { amount: values.amount, currency: values.currency },
  validFrom: values.validFrom,
  ...(values.validTo === "" ? {} : { validTo: values.validTo }),
});

/** The reason an amount typed as text is refused, judged on its characters and never on its value as a number. */
function amountReason(amount: string): FieldReason | undefined {
  if (amount === "") return "required";
  if (amount.startsWith("-")) return "amount-positive";
  if (!AMOUNT_FORMAT.test(amount)) return "amount-format";
  // A zero has no digit from 1 to 9 ("0", "0.00"): the server judges the same, and a price must be above zero.
  return /[1-9]/.test(amount) ? undefined : "amount-positive";
}

/**
 * The reasons the form is refused before sending: the generated request for the concept and the dates, the amount and
 * the currency judged here (they travel nested in the request), and an end date before the start. ISO dates compare
 * correctly as text.
 */
export function validatePrice(values: PriceValues): Partial<Record<PriceField, FieldReason>> {
  const fromSchema = reasonsFromSchema(AddPriceRequest, addBodyOf(values), ["paymentConceptId", "validFrom", "validTo"]);
  const amount = amountReason(values.amount);
  const reasons: Partial<Record<PriceField, FieldReason>> = {
    ...fromSchema,
    ...(amount === undefined ? {} : { amount }),
    ...(values.currency === "" ? { currency: "required" as const } : (CURRENCIES as readonly string[]).includes(values.currency) ? {} : { currency: "format" as const }),
    ...requiredReasons(values, ["paymentConceptId", "validFrom"]),
  };
  if (reasons.validFrom === undefined && reasons.validTo === undefined && values.validTo !== "" && values.validTo < values.validFrom) {
    reasons.validTo = "validity-order";
  }
  return reasons;
}

/** One concept's prices, newest validity first, as the detail shows them. */
export interface ConceptPrices {
  readonly paymentConceptId: string;
  readonly items: readonly PriceItem[];
}

/**
 * Groups the items of a list by concept, in the order each concept first appears, with each group's validities from the
 * newest start to the oldest. It only arranges what the server sent; it never adds, drops or changes an item.
 */
export function groupByConcept(items: readonly PriceItem[]): readonly ConceptPrices[] {
  const groups = new Map<string, PriceItem[]>();
  for (const item of items) {
    const group = groups.get(item.paymentConceptId);
    if (group === undefined) groups.set(item.paymentConceptId, [item]);
    else group.push(item);
  }
  return [...groups].map(([paymentConceptId, group]) => ({
    paymentConceptId,
    items: [...group].sort((a, b) => (a.validFrom < b.validFrom ? 1 : a.validFrom > b.validFrom ? -1 : 0)),
  }));
}
