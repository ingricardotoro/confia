import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

// Specs/build-integrity, requirement "Reglas de dependencia del frontend"
// (frontend-monorepo-and-contracts-pipeline, design.md decision 8; task 3.1). The same
// dependency-cruiser run CI performs, over two trees: the deliberate violations, where every rule
// must reject something, and the real apps/ and packages/, where none may, over a set that is
// demonstrably not empty.

const RULES = [
  "packages-never-import-apps",
  "apps-never-import-other-apps",
  "contracts-only-through-public-entry",
  "portal-never-imports-admin-contracts",
] as const;

const toolingRoot = join(dirname(fileURLToPath(import.meta.url)), "..");
const repositoryRoot = join(toolingRoot, "..");
// dependency-cruiser exports neither its package.json nor its bin, so the path pnpm links into this
// package's node_modules is used directly.
const cruiser = join(toolingRoot, "node_modules", "dependency-cruiser", "bin", "dependency-cruiser.mjs");

interface CruiseResult {
  summary: {
    violations: { rule: { name: string } }[];
    totalCruised: number;
  };
}

function cruise(...paths: string[]): CruiseResult {
  const run = spawnSync(
    process.execPath,
    [cruiser, "--config", ".dependency-cruiser.cjs", "--output-type", "json", ...paths],
    { cwd: repositoryRoot, encoding: "utf8", maxBuffer: 64 * 1024 * 1024 },
  );
  if (run.stdout.trim() === "") {
    throw new Error(`dependency-cruiser produced no output: ${run.stderr}`);
  }
  return JSON.parse(run.stdout) as CruiseResult;
}

describe("the frontend dependency rules", () => {
  const fixtures = cruise("tooling/dependency-fixtures");
  const violatedRules = fixtures.summary.violations.map((violation) => violation.rule.name);

  it.each(RULES)("%s rejects its deliberate violation", (rule) => {
    expect(violatedRules).toContain(rule);
  });

  it("finds no violation in the real tree, over a set that is not empty", () => {
    const real = cruise("apps", "packages");
    expect(real.summary.totalCruised).toBeGreaterThan(0);
    expect(real.summary.violations).toEqual([]);
  });

  // Build and report output is generated, not authored: Storybook's bundles under dist/ made this
  // run slower than the 5 s limit (task 3.1). Each directory below must stay out of the cruise.
  it.each(["dist", "coverage", "reports"])("does not cruise generated output under %s/", (directory) => {
    const folder = join(repositoryRoot, "packages", "ui", directory);
    const created = !existsSync(folder);
    mkdirSync(folder, { recursive: true });
    const probe = join(folder, "cruise-probe.js");
    writeFileSync(probe, "export const probe = 1;");
    try {
      expect(cruise(`packages/ui/${directory}`).summary.totalCruised).toBe(0);
    } finally {
      rmSync(probe, { force: true });
      if (created) rmSync(folder, { recursive: true, force: true });
    }
  });
});
