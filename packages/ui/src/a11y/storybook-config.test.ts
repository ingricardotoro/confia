import { globSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import config from "../../.storybook/main";
import { listFiles } from "../test/files";

// design.md section 10, "executable MDX": Storybook accepts MDX, and MDX is code. Only CSF stories
// in .stories.tsx files exist in this package, and nothing in the configuration can admit more.
const packageRoot = join(import.meta.dirname, "..", "..");
const isMdx = (name: string) => name.toLowerCase().endsWith(".mdx");
const admitsMdx = (patterns: readonly unknown[]) =>
  patterns.some((pattern) => typeof pattern !== "string" || !pattern.endsWith(".stories.tsx") || /mdx/i.test(pattern));

describe("the Storybook configuration", () => {
  it("has no MDX file anywhere in the package", () => {
    const authored = listFiles(packageRoot, () => true);

    expect(authored.length).toBeGreaterThan(0);
    expect(authored.filter(isMdx)).toEqual([]);
  });

  it("finds an MDX file when one exists (deliberate violation)", () => {
    const root = mkdtempSync(join(tmpdir(), "confia-mdx-"));
    try {
      mkdirSync(join(root, "src"));
      writeFileSync(join(root, "src", "Intro.mdx"), "# Intro\n");

      expect(listFiles(root, isMdx)).toEqual(["src/Intro.mdx"]);
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  });

  it("discovers stories only in .stories.tsx files", () => {
    expect(Array.isArray(config.stories)).toBe(true);
    expect(config.stories).not.toHaveLength(0);
    expect(admitsMdx(config.stories as unknown[])).toBe(false);
  });

  it("publishes the design system stories and never a deliberately inaccessible fixture", () => {
    const published = (config.stories as string[]).flatMap((pattern) =>
      globSync(pattern.replace("../", ""), { cwd: packageRoot }).map((path) => path.replaceAll("\\", "/")),
    );

    expect(published).toContain("src/atoms/spinner/Spinner.stories.tsx");
    expect(published.filter((path) => path.includes("__fixtures__"))).toEqual([]);
  });

  it("would notice a pattern that admits MDX (deliberate violation)", () => {
    expect(admitsMdx(["../src/**/*.@(mdx|stories.tsx)"])).toBe(true);
    expect(admitsMdx(["../src/**/*.mdx"])).toBe(true);
    expect(admitsMdx([{ directory: "../src" }])).toBe(true);
  });

  it("loads no addon that compiles MDX", () => {
    const addons = (config.addons ?? []).map((addon) => (typeof addon === "string" ? addon : addon.name));

    expect(addons).toContain("@storybook/addon-a11y");
    expect(addons.filter((name) => /docs|mdx/i.test(name))).toEqual([]);
  });
});
