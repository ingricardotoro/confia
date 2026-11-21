import type { MovementResponseOutput, RecordMovementRequest, SessionResponseOutput, SessionSummaryResponseOutput } from "@confia/contracts/admin";

// Types the presentation components of this feature read. They come from the generated contracts, not from shared/api: a
// presentation component never imports the API client module, not even for a type (admin-presentation-is-pure).
export type CashSession = SessionResponseOutput;
export type CashSessionRow = SessionSummaryResponseOutput;
export type CashSessionStatus = SessionResponseOutput["status"];
export type CashMovement = MovementResponseOutput;
/** The movements a person records (a deposit, a withdrawal, an adjustment). */
export type CashMovementType = RecordMovementRequest["type"];
/** Every movement a session lists, which also has the cash a payment left in it (`payment_in`, written by the payments, never by the person). */
export type CashMovementRowType = MovementResponseOutput["type"];
