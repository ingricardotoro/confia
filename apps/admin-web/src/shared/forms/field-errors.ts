import type { AdminKey } from "../../i18n/resources.ts";

/** One error of one field, as the server sends it or as a form's own validation finds it. */
export interface FieldIssue {
  readonly field: string;
  readonly reason: string;
}

/** One line of the error summary: the control it names and the catalog key of its message. */
export interface SummaryEntry {
  readonly controlId: string;
  readonly key: AdminKey;
}

export interface ResolvedFieldErrors<F extends string> {
  readonly byField: Partial<Record<F, AdminKey>>;
  readonly summary: readonly SummaryEntry[];
}

/**
 * The catalog key of a reason (design.md E5). `not-blank` and the local `required` mean the field is empty;
 * `size`, `max` and `too-long` mean it is too long; `end-after-start`, `positive-whole` and `whole` (and the server's
 * `positive` and `min`), the birth date rules of a student and the format of a national id have a text of their
 * own; every other reason, `format` included, gets the generic text of the field (AW51). A key never contains the rejected value, because none is built from it (SS41).
 */
export function fieldErrorKey(reason: string): AdminKey {
  switch (reason) {
    case "not-blank":
    case "required":
      return "field.required";
    case "size":
    case "max":
    case "too-long":
      return "field.tooLong";
    case "end-after-start":
      return "field.endAfterStart";
    case "positive-whole":
    case "positive":
      return "field.positiveWhole";
    case "whole":
    case "min":
      return "field.wholeNumber";
    case "past-or-present":
      return "field.birthDateFuture";
    case "birth-date-floor":
      return "field.birthDateFloor";
    case "national-id-format":
      return "field.nationalIdFormat";
    case "reason-length":
      return "field.reasonLength";
    case "enrollment-date-out-of-range":
      return "field.dateOutOfRange";
    case "same-section":
      return "field.sameSection";
    case "rtn-format":
      return "field.rtnFormat";
    case "email-format":
      return "field.emailFormat";
    case "phone-format":
      return "field.phoneFormat";
    // The server reads a percentage with more than two decimals as `digits` and one out of 0.01 to 100 as `decimal-min` or `decimal-max`.
    case "percent-format":
    case "digits":
      return "field.percentFormat";
    case "percent-range":
    case "decimal-min":
    case "decimal-max":
      return "field.percentRange";
    case "policy-version-format":
      return "field.policyVersionFormat";
    case "not-consentable":
      return "field.notConsentable";
    case "code-format":
      return "catalog.paymentConcepts.errors.codeFormat";
    case "duplicate-payment-concept-code":
      return "catalog.paymentConcepts.errors.duplicateCode";
    case "amount-format":
      return "catalog.priceLists.errors.amountFormat";
    case "amount-positive":
      return "catalog.priceLists.errors.amountPositive";
    case "date-range":
    case "validity-day":
      return "catalog.priceLists.errors.dateRange";
    case "validity-order":
      return "catalog.priceLists.errors.validityOrder";
    case "duplicate-tax-rate-code":
      return "catalog.taxRates.errors.duplicateCode";
    case "duplicate-scholarship-code":
      return "scholarships.errors.duplicateCode";
    case "duplicate-discount-code":
      return "scholarships.errors.duplicateDiscountCode";
    case "assignments-outside-validity":
      return "scholarships.errors.assignmentsOutsideValidity";
    case "outside-academic-year":
      return "scholarships.errors.outsideAcademicYear";
    case "duplicate-account-code":
      return "ledger.accounts.errors.duplicateCode";
    case "rate-format":
      return "catalog.taxRates.errors.rateFormat";
    case "due-day-range":
      return "charges.errors.dueDayRange";
    case "periods-range":
      return "charges.errors.periodsRange";
    case "first-of-month":
      return "charges.errors.firstOfMonth";
    case "rate-range":
      return "catalog.taxRates.errors.rateRange";
    case "cash-reason-length":
      return "cashbox.errors.reasonLength";
    case "cash-reason-control":
      return "cashbox.errors.reasonControl";
    case "difference-reason-required":
      return "cashbox.errors.differenceReasonRequired";
    case "reference-length":
      return "payments.errors.referenceLength";
    case "cash-received-today":
      return "payments.errors.cashReceivedToday";
    case "duplicate-transfer-reference":
      return "payments.errors.duplicateReference";
    case "not-allowed":
      return "field.notAllowed";
    default:
      return "field.invalid";
  }
}

/**
 * Puts each error under its field and in the summary, in the order received. A field the form does not
 * have still enters the summary, as text, with a control id that is not in the document (`unknown-field-<n>`),
 * so no error is dropped (AW51). Of several errors for one field only the first is kept.
 */
export function resolveFieldErrors<F extends string>(
  issues: readonly FieldIssue[],
  controlIds: Readonly<Record<F, string>>,
): ResolvedFieldErrors<F> {
  const byField: Partial<Record<F, AdminKey>> = {};
  const summary: SummaryEntry[] = [];
  let unknown = 0;

  for (const { field, reason } of issues) {
    if (!Object.hasOwn(controlIds, field)) {
      unknown += 1;
      summary.push({ controlId: `unknown-field-${String(unknown)}`, key: "field.unknown" });
    } else if (!Object.hasOwn(byField, field)) {
      const key = fieldErrorKey(reason);
      byField[field as F] = key;
      summary.push({ controlId: controlIds[field as F], key });
    }
  }
  return { byField, summary };
}
