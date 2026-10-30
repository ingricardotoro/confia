import { globSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import config from "../vitest.config";

const packageRoot = join(import.meta.dirname, "..");
const normalize = (path: string) => path.replaceAll("\\", "/");

// build-integrity BX20: a test file the runner does not collect must fail the build instead of
// being skipped. If the shared include list lost "src/**/*.test.tsx", the only symptom would be
// missing tests, and the *.test.ts files that remain would keep the run green. The include is read
// from the projects of this package's own vitest.config.ts, so a local override is what gets checked.
// A test file is collected by exactly one project: the browser tests (*.a11y.test.tsx) belong to
// "a11y" and are excluded from "unit", which has no browser. Fixture trees hold no tests.
const projects = (config.test?.projects ?? []).filter(
  (project): project is { test: { name: string; include?: string[]; exclude?: string[] } } =>
    typeof project === "object" && "test" in project,
);
const collectedBy = (name: string) => {
  const test = projects.find((project) => project.test.name === name)?.test;
  const excluded = new Set(test?.exclude?.flatMap((pattern) => globSync(pattern, { cwd: packageRoot })).map(normalize));
  return new Set(
    test?.include
      ?.flatMap((pattern) => globSync(pattern, { cwd: packageRoot }))
      .map(normalize)
      .filter((path) => !excluded.has(path)),
  );
};

describe("the test collection", () => {
  const unit = collectedBy("unit");
  const browser = collectedBy("a11y");
  const onDisk = readdirSync(join(packageRoot, "src"), { recursive: true })
    .map((entry) => normalize(`src/${entry.toString()}`))
    .filter((path) => /\.test\.tsx?$/.test(path) && !path.includes("/__fixtures__/"));

  it("evaluates a real set of test files", () => {
    expect(onDisk.length).toBeGreaterThan(0);
    expect(browser.size).toBeGreaterThan(0);
  });

  it("collects every *.test.ts and *.test.tsx file under src in some project", () => {
    const skipped = onDisk.filter((path) => !unit.has(path) && !browser.has(path));

    expect(skipped).toEqual([]);
  });

  it("collects no test file twice, and no browser test in the jsdom project", () => {
    expect([...unit].filter((path) => browser.has(path))).toEqual([]);
    expect([...unit].filter((path) => path.endsWith(".a11y.test.tsx"))).toEqual([]);
  });

  it("collects no test file from a fixture tree", () => {
    expect([...unit, ...browser].filter((path) => path.includes("/__fixtures__/"))).toEqual([]);
  });
});
