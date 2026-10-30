import { describe, expect, it } from "vitest";
import { baseDeclarations, readToken } from "./__fixtures__/css";

// design.md decision 5 and docs/ui-ux/01 section 7.1.
describe("the font stack", () => {
  const stack = (baseDeclarations([readToken("semantic.css")]).get("--font-sans") ?? "")
    .split(",")
    .map((part) => part.trim());

  it("starts with Inter and falls back to the system fonts [DS14]", () => {
    expect(stack.slice(0, 4)).toEqual(["'Inter Variable'", "'Inter'", "ui-sans-serif", "system-ui"]);
    expect(stack).toContain("sans-serif");
  });
});

describe("base.css", () => {
  const base = readToken("base.css");
  const faceSources = [...base.matchAll(/url\(\s*['"]?([^'")]+)['"]?\s*\)/g)].map((match) => match[1] ?? "");

  it("serves Inter from the package, only the latin and latin-ext subsets [DS13]", () => {
    expect(faceSources.toSorted()).toEqual([
      "@fontsource-variable/inter/files/inter-latin-ext-wght-normal.woff2",
      "@fontsource-variable/inter/files/inter-latin-wght-normal.woff2",
    ]);
  });

  it("asks no third party for a font [DS13]", () => {
    expect(base).not.toMatch(/https?:/);
    expect(base).not.toMatch(/fonts\.(googleapis|gstatic)/);
  });
});
