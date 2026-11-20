import type { FieldReason } from "../../shared/forms/use-access-form.ts";

/** The bounds of the reason of a difference and of the notes of an adjustment, as the server holds them (20 to 500 characters once stripped of border spaces). */
export const CASH_REASON_MIN = 20;
export const CASH_REASON_MAX = 500;
/** The largest note of a deposit, a withdrawal or an approval. */
export const CASH_NOTE_MAX = 500;
/** The currency of a session: the institution's, which today is the lempira. The server names it too, so a mismatch is refused there. */
export const CASH_CURRENCY = "HNL";

/** What the server takes as an amount: digits, up to ten before the point and two after. No sign, no separators. */
const AMOUNT_FORMAT = /^\d{1,10}(\.\d{1,2})?$/;
/** Control and format characters, which the server refuses (a line break is one of them). */
const CONTROL_OR_FORMAT = /[\p{Cc}\p{Cf}]/u;

/**
 * The reason an amount typed as text is refused, judged on its characters and never on its value as a number: the browser neither
 * parses nor rounds it, and the server answers with it rounded. `positive` refuses a zero (a movement of nothing is no movement);
 * the float and the counted cash accept it.
 */
export function amountReasonOf(amount: string, positive: boolean): FieldReason | undefined {
  if (amount === "") return "required";
  if (positive && amount.startsWith("-")) return "amount-positive";
  if (!AMOUNT_FORMAT.test(amount)) return "amount-format";
  // A zero has no digit from 1 to 9 ("0", "0.00").
  return positive && !/[1-9]/.test(amount) ? "amount-positive" : undefined;
}

/** How many characters the server counts: code points, so an emoji counts once. */
const lengthOf = (text: string): number => Array.from(text.trim()).length;

/**
 * The reason a free text is refused. `required` texts (the notes of an adjustment, the reason of a difference when the person
 * writes one) are bounded below; an optional one may be empty. The server decides whether a difference needs its reason: this only
 * spares the person the refusals it can see.
 */
export function noteReasonOf(text: string, rule: { readonly min: number; readonly max: number; readonly required: boolean }): FieldReason | undefined {
  const length = lengthOf(text);
  if (length === 0) return rule.required ? "required" : undefined;
  if (length < rule.min || length > rule.max) return rule.min > 1 ? "cash-reason-length" : "too-long";
  return CONTROL_OR_FORMAT.test(text.trim()) ? "cash-reason-control" : undefined;
}

/**
 * What the person confirmed, as a fingerprint of the operation, the session and the body. The key is made once for the intent and
 * sent on every try of it, so a retry after a network failure (the server may or may not have done it) answers the same request and
 * never a second one. Anything that changed is another request body, so it gets its own key: the server answers the same key with
 * another body with a refusal.
 */
export interface CashIntent {
  readonly fingerprint: string;
  readonly idempotencyKey: string;
}

/** The intent to send now: the previous one when it is the same request, a new one (with a new key) otherwise. */
export function intentFor(previous: CashIntent | undefined, fingerprint: string, random: () => string = () => crypto.randomUUID()): CashIntent {
  return previous?.fingerprint === fingerprint ? previous : { fingerprint, idempotencyKey: random() };
}
