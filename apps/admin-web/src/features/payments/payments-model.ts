import { amountReasonOf, noteReasonOf } from "../../shared/forms/text-rules.ts";
import type { FieldReason } from "../../shared/forms/use-access-form.ts";
import type { PaymentMethod } from "./payments-types.ts";

/** The currency of a payment: the one of the institution, which today is the lempira. The server names it too, so a mismatch is refused there. */
export const PAYMENT_CURRENCY = "HNL";
/** The bounds of the bank reference of a transfer, as the server holds them. */
export const REFERENCE_MIN = 4;
export const REFERENCE_MAX = 40;
export const BANK_NAME_MAX = 80;
export const NOTES_MAX = 500;
/** The bounds of the reason of a rejection (20 to 500 characters once stripped of border spaces). */
export const REJECTION_REASON_MIN = 20;
export const REJECTION_REASON_MAX = 500;

export type RegisterField = "studentId" | "method" | "amount" | "receivedOn" | "reference" | "bankName" | "notes";
export type RegisterValues = Readonly<Record<RegisterField, string>>;

const ISO_DAY = /^\d{4}-\d{2}-\d{2}$/;

/** Today in the time zone of the institution, which is the date a payment is received on by default and the only one a cash payment accepts. */
export function todayIso(now: Date = new Date()): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "America/Tegucigalpa", year: "numeric", month: "2-digit", day: "2-digit" }).format(now);
}

/** How many characters the server counts: code points, so an emoji counts once. */
const lengthOf = (text: string): number => Array.from(text.trim()).length;

export const isTransfer = (method: string): method is Extract<PaymentMethod, "BANK_TRANSFER"> => method === "BANK_TRANSFER";

/**
 * What the form refuses before sending, judged on the characters and never on a value as a number. The server decides the rest (a date
 * in the future, a reference already used, an amount over what it accepts); this only spares the person the refusals it can see. A cash
 * payment has no reference and no bank, and the server refuses them, so they are not asked for.
 */
export function registerReasonsOf(values: RegisterValues): Partial<Record<RegisterField, FieldReason>> {
  const reasons: Partial<Record<RegisterField, FieldReason>> = {};
  if (values.studentId === "") reasons.studentId = "required";
  const amount = amountReasonOf(values.amount, true);
  if (amount !== undefined) reasons.amount = amount;
  if (!ISO_DAY.test(values.receivedOn)) reasons.receivedOn = "required";
  if (isTransfer(values.method)) {
    const length = lengthOf(values.reference);
    if (length === 0) reasons.reference = "required";
    else if (length < REFERENCE_MIN || length > REFERENCE_MAX) reasons.reference = "reference-length";
    const bank = values.bankName.trim();
    if (bank === "") reasons.bankName = "required";
    else if (lengthOf(bank) > BANK_NAME_MAX) reasons.bankName = "too-long";
  }
  const notes = noteReasonOf(values.notes, { min: 1, max: NOTES_MAX, required: false });
  if (notes !== undefined) reasons.notes = notes;
  return reasons;
}

/** The reason of a rejection: required, 20 to 500 characters, and no control characters. */
export const rejectionReasonOf = (text: string): FieldReason | undefined =>
  noteReasonOf(text, { min: REJECTION_REASON_MIN, max: REJECTION_REASON_MAX, required: true });
