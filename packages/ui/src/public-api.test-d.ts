import { describe, expectTypeOf, it } from "vitest";
import type { MoneyAmount as MoneyAmountByName } from "@confia/ui";
import type { MoneyAmount } from "./domain/money-amount";

// ADR-0018 static check (DS01, DS03): the design system of this delivery has seventeen components; the
// rest of the P0 inventory of docs/ui-ux/02 arrives in 13b. Importing one of them must not compile.
// Each `@ts-expect-error` marks a permanent, deliberate violation; if the line stops failing, tsc reports
// an unused directive and this file turns red.
describe("the public entry point, as the package name resolves it", () => {
  it("DS01: gives MoneyAmount through the package name", () => {
    expectTypeOf<typeof MoneyAmountByName>().toEqualTypeOf<typeof MoneyAmount>();
  });

  it("DS03: has neither DataTable nor Select", async () => {
    // @ts-expect-error ADR-0018: DataTable is not part of this delivery
    const { DataTable } = await import("@confia/ui");
    // @ts-expect-error ADR-0018: Select is not part of this delivery
    const { Select } = await import("@confia/ui");

    expectTypeOf(DataTable).toBeAny();
    expectTypeOf(Select).toBeAny();
  });
});
