// Deliberate violation of portal-never-imports-admin-contracts: the portal compiling against the
// administrative contract.
import { Money } from "../../../../packages/contracts/src/admin.ts";

export const leaked = Money;
