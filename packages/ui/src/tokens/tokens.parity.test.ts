import { describe, expect, it } from "vitest";
import {
  DARK_ATTRIBUTE_CONTEXT,
  DARK_MEDIA_CONTEXT,
  darkBlocks,
  parityReport,
  readFixture,
  realTokenSources,
} from "./__fixtures__/css";

// docs/ui-ux/01 section 6 and design.md decision 4.
describe("the dark theme blocks", () => {
  it("declare exactly the same variables with the same values, each with a base value [DS12]", () => {
    const report = parityReport(realTokenSources());

    expect(report.evaluated).toBeGreaterThan(0);
    expect(report.problems).toEqual([]);
  });

  it("name a variable that exists only in the dark blocks [DS11]", () => {
    const report = parityReport([readFixture("dark-only.css")]);

    expect(report.problems).toEqual(["--color-dark-only has no base value outside the dark blocks"]);
  });

  it("name a variable missing from one dark block, and a value that differs", () => {
    const report = parityReport([readFixture("dark-drift.css")]);

    expect(report.problems).toEqual([
      "--color-bg-surface differs between the dark blocks: #0B1220 vs #0B1221",
      "--color-text-primary is missing from the data-theme='dark' block",
    ]);
  });

  describe("with the real selectors", () => {
    const { media, attribute } = darkBlocks(realTokenSources());
    const mediaSelector = DARK_MEDIA_CONTEXT[1] ?? "";
    const attributeSelector = DARK_ATTRIBUTE_CONTEXT[0] ?? "";
    const root = document.documentElement;

    it("exist, the first under the exact system-preference media query", () => {
      expect(media).toBeDefined();
      expect(attribute).toBeDefined();
      expect(DARK_MEDIA_CONTEXT[0]).toBe("@media (prefers-color-scheme: dark)");
    });

    it("apply the system-preference block when the page chose nothing [DS08]", () => {
      root.removeAttribute("data-theme");

      expect(root.matches(mediaSelector)).toBe(true);
      expect(root.matches(attributeSelector)).toBe(false);
    });

    it("apply neither block when the attribute says light, so the attribute beats the preference [DS09]", () => {
      root.setAttribute("data-theme", "light");

      expect(root.matches(mediaSelector)).toBe(false);
      expect(root.matches(attributeSelector)).toBe(false);
      root.removeAttribute("data-theme");
    });

    it("apply the attribute block when the attribute says dark, whatever the system prefers [DS10]", () => {
      root.setAttribute("data-theme", "dark");

      expect(root.matches(attributeSelector)).toBe(true);
      expect(root.matches(mediaSelector)).toBe(true);
      root.removeAttribute("data-theme");
    });
  });
});
