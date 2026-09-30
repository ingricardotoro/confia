import { expectTypeOf, test } from "vitest";
import type { Money as AdminMoney } from "./admin.js";
import type { Money as PortalMoney } from "./portal.js";

// Specs/build-integrity, "Cambio incompatible del contrato" (task 2.4). If the snapshot ever
// declared amount as a number, the regenerated type would stop being a string and this file would
// stop compiling, which is what makes the change fail in CI and not in production.
test("an amount travels as two strings on both surfaces", () => {
  expectTypeOf<AdminMoney>().toEqualTypeOf<{ amount: string; currency: string }>();
  expectTypeOf<PortalMoney>().toEqualTypeOf<{ amount: string; currency: string }>();
});
