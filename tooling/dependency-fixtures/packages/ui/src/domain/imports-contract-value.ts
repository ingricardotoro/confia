// Deliberate violation of ui-contracts-type-only: a runtime value taken from the contract through
// its public entry point. Only types may come from there.
import { Money } from "@confia/contracts/portal";

export const leaked = Money;
