import { describe, expect, it } from "vitest";
import { baseDeclarations, readToken, resolveToken } from "./__fixtures__/css";

// design.md decision 3: component -> semantic -> primitive, by reference at every step.
describe("the three-level token chain", () => {
  const declarations = baseDeclarations([
    readToken("primitives.css"),
    readToken("semantic.css"),
    readToken("components.css"),
  ]);

  it("makes a semantic token a reference to a primitive [DS07]", () => {
    expect(declarations.get("--color-primary")).toBe("var(--palette-primary-600)");
    expect(declarations.get("--color-text-secondary")).toBe("var(--palette-neutral-600)");
  });

  it("makes a component token a reference to a semantic token [DS07]", () => {
    expect(declarations.get("--button-primary-bg")).toBe("var(--color-primary)");
    expect(declarations.get("--status-badge-paid-bg")).toBe("var(--color-status-paid-bg)");
  });

  it("resolves the component token down to the brand hex", () => {
    expect(resolveToken("--button-primary-bg", declarations).toLowerCase()).toBe("#2563eb");
  });

  it("changes a component color by editing only the primitive [DS07]", () => {
    const rebranded = new Map(declarations);
    rebranded.set("--palette-primary-600", "#0F766E");

    expect(resolveToken("--button-primary-bg", rebranded)).toBe("#0F766E");
    expect(readToken("components.css")).not.toContain("--palette-");
  });
});
