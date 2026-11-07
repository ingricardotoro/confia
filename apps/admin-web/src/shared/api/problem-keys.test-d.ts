import { describe, expectTypeOf, it } from "vitest";
import type { AdminKey } from "../../i18n/resources.ts";
import type { ProblemCode } from "./problem.ts";
import { PROBLEM_KEYS } from "./problem-keys.ts";

// ADR-0011 static check (AW48): the translation from code to catalog key is exhaustive by construction. The @ts-expect-error
// below is a permanent, deliberate violation: it adds a code to the union without adding it to the
// map, and tsc must fail there. If the line stops failing, tsc reports an unused directive.
describe("the problem key map", () => {
  it("has a catalog key for every known code and for the unknown case", () => {
    expectTypeOf(PROBLEM_KEYS).toEqualTypeOf<Readonly<Record<ProblemCode | "unknown", AdminKey>>>();
  });

  it("AW48: a code added to the union without a key does not compile", () => {
    type WithNewCode = Readonly<Record<ProblemCode | "brand-new-code", AdminKey>>;
    // @ts-expect-error ADR-0011 (AW48): deliberate violation: "brand-new-code" has no key in the map
    const incomplete: WithNewCode = PROBLEM_KEYS;
    expectTypeOf(incomplete).not.toBeNever();
  });
});
