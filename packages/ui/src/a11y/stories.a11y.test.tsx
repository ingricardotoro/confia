import { composeStories, setProjectAnnotations } from "@storybook/react-vite";
import { afterAll, describe, expect, it } from "vitest";
import { commands } from "vitest/browser";
import preview from "../../.storybook/preview";
import { assertStoriesDiscovered } from "./discovery";
import { THEMES, checkStoryInAllThemes, describeBlocking, summarize, type GateResult, type GateStory } from "./run-gate";

// design.md decision 8: every story of the design system, in both themes, in a real browser with the
// real stylesheet. Critical and serious violations fail; moderate and minor are written to
// reports/a11y/report.json, which CI uploads (docs/ui-ux/03 section 11).
setProjectAnnotations([preview]);

const modules = import.meta.glob<Record<string, unknown>>("../{atoms,molecules,domain}/**/*.stories.tsx", {
  eager: true,
});
assertStoriesDiscovered(modules);

const stories = Object.entries(modules).flatMap(([path, module]) =>
  Object.entries<GateStory>(composeStories(module as Parameters<typeof composeStories>[0]) as Record<string, GateStory>).map(([name, story]) => ({
    label: `${path.replace("../", "")} ${name}`,
    story,
  })),
);
const collected: GateResult[] = [];

afterAll(async () => {
  await commands.writeA11yReport(JSON.stringify(summarize(collected), null, 2));
});

describe("every story of the design system", () => {
  it("discovers at least one story [DS71]", () => {
    expect(stories.length).toBeGreaterThan(0);
  });

  it.each(stories)("has no critical or serious violation in $label", async ({ story }) => {
    const results = await checkStoryInAllThemes(story);
    collected.push(...results);

    expect(results.map((result) => result.theme)).toEqual([...THEMES]);
    expect(describeBlocking(results), "blocking violations").toBe("");
  });
});

describe("the browser environment", () => {
  it("requests no resource from an origin other than the local one [DS13]", async () => {
    await document.fonts.load('1rem "Inter Variable"');
    const origins = performance.getEntriesByType("resource").map((entry) => new URL(entry.name).origin);

    expect(origins.length).toBeGreaterThan(0);
    expect(new Set(origins)).toEqual(new Set([location.origin]));
  });

  it("serves the font file from the package itself [DS13]", async () => {
    await document.fonts.load('1rem "Inter Variable"');
    const fonts = performance.getEntriesByType("resource").filter((entry) => /\.woff2/.test(entry.name));

    expect(fonts.length).toBeGreaterThan(0);
  });

  it("takes the dark values from the system preference when no data-theme is set [DS08]", async () => {
    const root = document.documentElement;
    const previousTheme = root.dataset["theme"];
    const canvas = () => getComputedStyle(root).getPropertyValue("--color-bg-canvas").trim();

    // Whatever fails below, the emulation and the attribute go back, or the next test inherits them.
    try {
      // The value the data-theme attribute gives, under a light system preference...
      await commands.emulateColorScheme("light");
      root.dataset["theme"] = "dark";
      const byAttribute = canvas();
      root.dataset["theme"] = "light";
      const light = canvas();
      // ...must equal what a dark system preference gives when no attribute is set.
      delete root.dataset["theme"];
      await commands.emulateColorScheme("dark");
      const bySystem = canvas();

      expect(byAttribute).not.toBe(light);
      expect(bySystem).toBe(byAttribute);
    } finally {
      await commands.emulateColorScheme("light");
      if (previousTheme === undefined) delete root.dataset["theme"];
      else root.dataset["theme"] = previousTheme;
    }
  });
});
