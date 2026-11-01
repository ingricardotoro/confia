// Deliberate violation of contracts-only-through-public-entry (and so of the contract mirror of the
// UI layers): an internal path of the contract, even for a type.
import type { Money } from "../../../../../../packages/contracts/src/generated/portal/schemas.ts";

export type Leaked = Money;
