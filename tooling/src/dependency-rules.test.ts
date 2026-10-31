import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { withProbeTree } from "./cruise-probe.js";

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

// `cwd` defaults to the repository; the generated-output probe cruises a temporary tree with the same
// configuration instead of writing into packages/.
function cruise(paths: string[], cwd: string = repositoryRoot): CruiseResult {
  const run = spawnSync(
    process.execPath,
    [cruiser, "--config", join(repositoryRoot, ".dependency-cruiser.cjs"), "--output-type", "json", ...paths],
    { cwd, encoding: "utf8", maxBuffer: 64 * 1024 * 1024 },
  );
  if (run.stdout.trim() === "") {
    throw new Error(`dependency-cruiser produced no output: ${run.stderr}`);
  }
  return JSON.parse(run.stdout) as CruiseResult;
}

describe("the frontend dependency rules", () => {
  const fixtures = cruise(["tooling/dependency-fixtures"]);
  const violatedRules = fixtures.summary.violations.map((violation) => violation.rule.name);

  it.each(RULES)("%s rejects its deliberate violation", (rule) => {
    expect(violatedRules).toContain(rule);
  });

  it("finds no violation in the real tree, over a set that is not empty", () => {
    const real = cruise(["apps", "packages"]);
    expect(real.summary.totalCruised).toBeGreaterThan(0);
    expect(real.summary.violations).toEqual([]);
  });

  // Build and report output is generated, not authored: Storybook's bundles under dist/ made this
  // run slower than the 5 s limit (task 3.1). Each directory below must stay out of the cruise. The
  // probe lives in a temporary tree, never under packages/, so it cannot delete output that the ui
  // build and test tasks write at the same time.
  it.each(["dist", "coverage", "reports"])("does not cruise generated output under %s/", (directory) => {
    withProbeTree(directory, (root) => {
      expect(cruise([`packages/ui/${directory}`], root).summary.totalCruised).toBe(0);
    });
  });

  it("does cruise authored code in the same temporary tree, so the probe can fail", () => {
    withProbeTree("src", (root) => {
      expect(cruise(["packages/ui/src"], root).summary.totalCruised).toBeGreaterThan(0);
    });
  });
});
