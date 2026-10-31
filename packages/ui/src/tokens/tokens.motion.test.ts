import { describe, expect, it } from "vitest";
import { parseRules, readToken } from "./__fixtures__/css";

// docs/ui-ux/01 section 10: the reduced-motion block is mandatory, and it shortens animations to
// almost nothing instead of using `animation: none`, so transitionend and animationend still fire.
// The Spinner (task 3.1) is the first component that animates, so the block lands with it.
describe("base.css motion", () => {
  const reduced = parseRules(readToken("base.css")).filter((rule) =>
    rule.context.some((prelude) => prelude.includes("prefers-reduced-motion: reduce")),
  );

  it("declares the reduced-motion block of docs/ui-ux/01 section 10", () => {
    expect(reduced.length).toBeGreaterThan(0);
    const declarations = Object.fromEntries(reduced.flatMap((rule) => [...rule.declarations]));

    expect(declarations["animation-duration"]).toMatch(/^0\.01ms/);
    expect(declarations["animation-iteration-count"]).toMatch(/^1\b/);
    expect(declarations["transition-duration"]).toMatch(/^0\.01ms/);
    expect(declarations["scroll-behavior"]).toMatch(/^auto/);
  });

  it("never disables an animation outright", () => {
    expect(readToken("base.css")).not.toMatch(/animation\s*:\s*none|transition\s*:\s*none/);
  });
});
