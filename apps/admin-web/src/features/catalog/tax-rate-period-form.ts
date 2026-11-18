import { AddTaxRatePeriodRequest, type AddTaxRatePeriodRequest as AddBody } from "@confia/contracts/admin";
import { reasonsFromSchema, requiredReasons } from "../../shared/forms/schema-reasons.ts";
import type { FieldReason } from "../../shared/forms/use-access-form.ts";
import { DATE_WINDOW } from "./price-form.ts";
import type { TaxRatePeriod } from "./tax-rate-form.ts";

export type TaxRatePeriodField = "ratePercent" | "validFrom" | "validTo";

export const TAX_RATE_PERIOD_CONTROL_IDS: Readonly<Record<TaxRatePeriodField, string>> = {
  ratePercent: "tax-rate-period-rate",
  validFrom: "tax-rate-period-valid-from",
  validTo: "tax-rate-period-valid-to",
};

export type TaxRatePeriodValues = Readonly<Record<TaxRatePeriodField, string>>;

export const initialTaxRatePeriodValues = (): TaxRatePeriodValues => ({ ratePercent: "", validFrom: "", validTo: "" });

/** What the server takes as a percentage: up to three digits, and up to four after the point. No sign, no separators, no `%`. */
const RATE_FORMAT = /^\d{1,3}(\.\d{1,4})?$/;

/**
 * The request of a new validity. The percentage stays the text that was typed: the browser neither parses nor rounds
 * it, and the server answers with it at four decimals. An empty end date is no end, so it is not sent.
 */
export const addBodyOf = (values: TaxRatePeriodValues): AddBody => ({
  ratePercent: values.ratePercent,
  validFrom: values.validFrom,
  ...(values.validTo === "" ? {} : { validTo: values.validTo }),
});

/**
 * Whether a text that already has the shape of a percentage is above 100, judged on its characters and never as a
 * number: the digits before the point, without leading zeros, are compared as text, and `100` with any non-zero
 * decimal is above it.
 */
function aboveHundred(rate: string): boolean {
  const [integer = "", decimals = ""] = rate.split(".");
  const whole = integer.replace(/^0+(?=\d)/, "");
  if (whole.length !== 3) return whole.length > 3;
  return whole > "100" || (whole === "100" && /[1-9]/.test(decimals));
}

/** The reason a percentage typed as text is refused, judged on its characters and never on its value as a number. */
function rateReason(rate: string): FieldReason | undefined {
  if (rate === "") return "required";
  if (rate.startsWith("-")) return "rate-range";
  if (!RATE_FORMAT.test(rate)) return "rate-format";
  // A zero has no digit from 1 to 9 ("0", "0.0000"): the server judges the same, and a rate must be above zero.
  if (!/[1-9]/.test(rate)) return "rate-range";
  return aboveHundred(rate) ? "rate-range" : undefined;
}

const outsideWindow = (date: string): boolean => date < DATE_WINDOW.first || date > DATE_WINDOW.last;

/**
 * The reasons the form is refused before sending: the generated request for the dates, the percentage judged here on
 * its characters, a start that is not blank, dates inside the window the server accepts (the same message the server's
 * `validity-day` gets) and an end before the start. ISO dates compare correctly as text.
 */
export function validateTaxRatePeriod(values: TaxRatePeriodValues): Partial<Record<TaxRatePeriodField, FieldReason>> {
  const fromSchema = reasonsFromSchema(AddTaxRatePeriodRequest, addBodyOf(values), ["validFrom", "validTo"]);
  const rate = rateReason(values.ratePercent);
  const reasons: Partial<Record<TaxRatePeriodField, FieldReason>> = {
    ...fromSchema,
    ...(rate === undefined ? {} : { ratePercent: rate }),
    ...requiredReasons(values, ["validFrom"]),
  };
  if (reasons.validFrom === undefined && outsideWindow(values.validFrom)) reasons.validFrom = "date-range";
  if (reasons.validTo === undefined && values.validTo !== "" && outsideWindow(values.validTo)) reasons.validTo = "date-range";
  if (reasons.validFrom === undefined && reasons.validTo === undefined && values.validTo !== "" && values.validTo < values.validFrom) {
    reasons.validTo = "validity-order";
  }
  return reasons;
}

/** The validities of a rate from the newest start to the oldest, as the history shows them. It only arranges what the server sent. */
export const newestFirst = (periods: readonly TaxRatePeriod[]): readonly TaxRatePeriod[] =>
  [...periods].sort((a, b) => (a.validFrom < b.validFrom ? 1 : a.validFrom > b.validFrom ? -1 : 0));
