import { expectTypeOf, test } from "vitest";
import type { Money as AdminMoney } from "@confia/contracts/admin";
import type { Money as PortalMoney } from "@confia/contracts/portal";
import type { MoneyAmountProps } from "./MoneyAmount";

// design.md decision 12: MoneyAmount takes the portal contract's Money (the least privileged one).
// The two documents declare the same shape today; if one of them drifts, the assignment below stops
// compiling and `typecheck` fails instead of the screen failing at runtime.
// Both contract imports are type-only, so no runtime contract code enters @confia/ui. Importing the
// administrative contract is allowed here because packages/ui is shared by both applications; the
// ban on it (ADR-0003) applies to apps/portal-web.
test("the Money of both contracts is accepted by MoneyAmount", () => {
  expectTypeOf<PortalMoney>().toExtend<MoneyAmountProps["value"]>();
  expectTypeOf<AdminMoney>().toExtend<MoneyAmountProps["value"]>();
});

test("MoneyAmount takes exactly the contract shape: two strings", () => {
  expectTypeOf<MoneyAmountProps["value"]>().toEqualTypeOf<PortalMoney>();
});
