import type { MovementResponseOutput, SessionResponseOutput, SessionSummaryResponseOutput } from "@confia/contracts/admin";

// Types the presentation components of this feature read. They come from the generated contracts, not from shared/api: a
// presentation component never imports the API client module, not even for a type (admin-presentation-is-pure).
export type CashSession = SessionResponseOutput;
export type CashSessionRow = SessionSummaryResponseOutput;
export type CashSessionStatus = SessionResponseOutput["status"];
export type CashMovement = MovementResponseOutput;
export type CashMovementType = MovementResponseOutput["type"];
