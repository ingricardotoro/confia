import { describe, expect, it } from "vitest";
import { buildCss, readToken } from "./__fixtures__/css";

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

  // The two dark blocks of themes.css always emit their selectors, so asserting those proves nothing
  // about @custom-variant. A utility with the dark: prefix exists only if the variant is defined, and
  // its rule is scoped by the variant's own selectors.
  it("scopes the dark: variant by both theme mechanisms [custom-variant]", async () => {
    const css = await buildCss(["dark:bg-bg-canvas"]);

    // Without @custom-variant dark, Tailwind has no dark: variant and drops the candidate.
    expect(css, "the dark:bg-bg-canvas utility was not generated").toContain(String.raw`.dark\:bg-bg-canvas`);
    expect(css).toMatch(
      /\.dark\\:bg-bg-canvas:where\(\[data-theme=["']dark["']\], \[data-theme=["']dark["']\] \*\)\s*\{[^}]*var\(--color-bg-canvas\)/,
    );
    expect(css).toMatch(
      /@media \(prefers-color-scheme: dark\)\s*\{\s*\.dark\\:bg-bg-canvas:where\(:not\(\[data-theme=["']light["']\], \[data-theme=["']light["']\] \*\)\)\s*\{[^}]*var\(--color-bg-canvas\)/,
    );
  });

  // The dark theme must override every token whose light value is a pair with another one, in both
  // mechanisms: a half-overridden pair is the way a dark theme loses its contrast.
  it.each(["--color-primary-soft-bg", "--color-primary-soft-fg", "--shadow-md", "--shadow-xl"])(
    "overrides %s in both dark blocks",
    (token) => {
      const themes = readToken("themes.css");
      const blocks = themes.split(/^:root\[data-theme='dark'\]/m);
      const media = blocks[0] ?? "";
      const attribute = blocks[1] ?? "";

      expect(media).toContain(`${token}:`);
      expect(attribute).toContain(`${token}:`);
    },
  );
});
