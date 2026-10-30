import { describe, expect, it } from "vitest";
import { buildCss } from "./__fixtures__/css";

// design.md decision 3: only semantic tokens generate utilities. Tailwind's own theme is cleared and
// the primitives live in a namespace Tailwind does not know.
describe("the utilities Tailwind generates from the token entry point", () => {
  it("generates a rule for a semantic token", async () => {
    const css = await buildCss(["bg-primary", "text-status-paid-fg"]);

    expect(css).toMatch(/\.bg-primary\s*\{[^}]*var\(--color-primary\)/);
    expect(css).toMatch(/\.text-status-paid-fg\s*\{[^}]*var\(--color-status-paid-fg\)/);
  });

  it("generates nothing for a primitive, whatever its name [DS04]", async () => {
    const css = await buildCss(["bg-palette-primary-600", "bg-primary-600", "bg-neutral-500"]);

    expect(css).not.toMatch(/\.bg-palette-primary-600/);
    expect(css).not.toMatch(/\.bg-primary-600/);
    expect(css).not.toMatch(/\.bg-neutral-500/);
  });

  it("generates nothing for Tailwind's default palette [DS04, DS05]", async () => {
    const css = await buildCss(["bg-red-500", "text-blue-600", "border-gray-200"]);

    expect(css).not.toMatch(/\.bg-red-500/);
    expect(css).not.toMatch(/\.text-blue-600/);
    expect(css).not.toMatch(/\.border-gray-200/);
  });

  it("still emits every primitive as a variable, used or not", async () => {
    const css = await buildCss([]);

    expect(css).toMatch(/--palette-primary-600:\s*#2563eb/i);
    expect(css).toMatch(/--palette-neutral-950:\s*#020617/i);
  });

  it("resolves the spacing, radius and type-size tokens of docs/ui-ux/01 [DS06]", async () => {
    const css = await buildCss(["p-4", "rounded-md", "text-sm", "font-semibold"]);

    expect(css).toMatch(/--spacing:\s*0?\.25rem/);
    expect(css).toMatch(/\.p-4\s*\{[^}]*calc\(var\(--spacing\) \* 4\)/);
    expect(css).toMatch(/--radius-md:\s*0?\.375rem/);
    expect(css).toMatch(/\.rounded-md\s*\{[^}]*var\(--radius-md\)/);
    expect(css).toMatch(/--text-sm:\s*0?\.875rem/);
    expect(css).toMatch(/--text-sm--line-height:\s*1\.25rem/);
    expect(css).toMatch(/\.text-sm\s*\{[^}]*var\(--text-sm\)/);
    expect(css).toMatch(/--font-weight-semibold:\s*600/);
  });

  it("makes the dark: variant follow both theme mechanisms", async () => {
    const css = await buildCss(["dark:bg-primary"]);

    expect(css).toContain("prefers-color-scheme: dark");
    expect(css).toMatch(/data-theme=["']dark["']/);
  });
});
