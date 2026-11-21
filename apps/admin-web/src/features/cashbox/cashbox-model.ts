/** The bounds of the reason of a difference and of the notes of an adjustment, as the server holds them (20 to 500 characters once stripped of border spaces). */
export const CASH_REASON_MIN = 20;
export const CASH_REASON_MAX = 500;
/** The largest note of a deposit, a withdrawal or an approval. */
export const CASH_NOTE_MAX = 500;
/** The currency of a session: the institution's, which today is the lempira. The server names it too, so a mismatch is refused there. */
export const CASH_CURRENCY = "HNL";

export { amountReasonOf, noteReasonOf } from "../../shared/forms/text-rules.ts";
export { intentFor } from "../../shared/forms/idempotent-intent.ts";
export type { IdempotentIntent as CashIntent } from "../../shared/forms/idempotent-intent.ts";
