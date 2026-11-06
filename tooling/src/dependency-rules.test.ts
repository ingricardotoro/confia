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
  "ui-atoms-know-only-tokens",
  "ui-molecules-never-import-domain",
  "ui-never-fetches-data",
  "ui-contracts-type-only",
  "apps-use-ui-public-entry",
  "admin-presentation-is-pure",
  "admin-shared-never-imports-features",
  "admin-token-store-is-private",
] as const;

// design-system-foundations-and-a11y, design.md decision 10 (task 6.3): each layer rule of packages/ui
// and the deliberate violation under tooling/dependency-fixtures/packages/ui/src that it must reject.
const LAYER_VIOLATIONS = [
  ["ui-atoms-know-only-tokens", "atoms/imports-a-molecule.ts"],
  ["ui-atoms-know-only-tokens", "atoms/imports-domain.ts"],
  ["ui-molecules-never-import-domain", "molecules/imports-domain.ts"],
  ["ui-never-fetches-data", "domain/imports-react-query.ts"],
  ["ui-contracts-type-only", "domain/imports-contract-value.ts"],
  ["contracts-only-through-public-entry", "domain/imports-contract-internal.ts"],
] as const;
const LAYERS = ["atoms", "molecules", "domain"] as const;

// admin-web-foundations-and-session, design.md D3 (task 1.1): each rule of apps/admin-web and the
// deliberate violation under tooling/dependency-fixtures/apps/admin-web/src that it must reject.
const APP_VIOLATIONS = [
  ["AW04", "apps-use-ui-public-entry", "imports-ui-internal.ts"],
  ["AW04", "apps-use-ui-public-entry", "imports-ui-subpath.ts"],
  ["BX58", "admin-presentation-is-pure", "features/identity/components/imports-query.ts"],
  ["BX59", "admin-presentation-is-pure", "features/identity/components/imports-api-relative.ts"],
  ["BX59", "admin-presentation-is-pure", "features/identity/components/imports-api-alias.ts"],
  ["BX60", "admin-presentation-is-pure", "features/identity/components/imports-api-type.ts"],
  ["D3", "admin-presentation-is-pure", "features/identity/components/imports-router.ts"],
  ["D3", "admin-presentation-is-pure", "features/identity/components/imports-form.ts"],
  ["D3", "admin-shared-never-imports-features", "shared/imports-a-feature.ts"],
  ["D3", "admin-token-store-is-private", "features/billing/reads-token-store.ts"],
] as const;
const APP_ACCEPTED = [
  "uses-ui-public-entry.ts",
  "features/identity/container.ts",
  "features/identity/session/access-session.ts",
] as const;

const toolingRoot = join(dirname(fileURLToPath(import.meta.url)), "..");
const repositoryRoot = join(toolingRoot, "..");
// dependency-cruiser exports neither its package.json nor its bin, so the path pnpm links into this
// package's node_modules is used directly.
const cruiser = join(toolingRoot, "node_modules", "dependency-cruiser", "bin", "dependency-cruiser.mjs");

interface CruiseResult {
  modules: { source: string; dependents: string[] }[];
  summary: {
    violations: { rule: { name: string }; from: string; to: string }[];
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

  it.each(LAYER_VIOLATIONS)("%s rejects %s", (rule, file) => {
    const flagged = fixtures.summary.violations.filter((violation) => violation.from.endsWith(`packages/ui/src/${file}`));

    expect(flagged.map((violation) => violation.rule.name)).toContain(rule);
  });

  it.each(APP_VIOLATIONS)("%s: %s rejects %s", (_id, rule, file) => {
    const flagged = fixtures.summary.violations.filter((violation) => violation.from.endsWith(`apps/admin-web/src/${file}`));

    expect(flagged.map((violation) => violation.rule.name)).toContain(rule);
  });

  it.each(APP_ACCEPTED)("BX61: accepts %s", (file) => {
    const accepted = `apps/admin-web/src/${file}`;

    expect(fixtures.modules.some((module) => module.source.endsWith(accepted))).toBe(true);
    expect(fixtures.summary.violations.filter((violation) => violation.from.endsWith(accepted))).toEqual([]);
  });

  it("BX31, BX36: accepts a domain module that imports atoms, molecules and a contract type", () => {
    const accepted = "packages/ui/src/domain/accepted-imports.ts";

    expect(fixtures.modules.some((module) => module.source.endsWith(accepted))).toBe(true);
    expect(fixtures.summary.violations.filter((violation) => violation.from.endsWith(accepted))).toEqual([]);
  });

  it("finds no violation in the real tree, over a set that is not empty", () => {
    const real = cruise(["apps", "packages"]);
    expect(real.summary.totalCruised).toBeGreaterThan(0);
    expect(real.summary.violations).toEqual([]);
  });

  // ADR-0018: apps/admin-web does not exist yet, so these two cannot evaluate real files. The task
  // that creates the files turns each entry into a test; a green run over an empty set is refused.
  it.todo("BX61: a real container of apps/admin-web imports TanStack Query and shared/api without a violation (task 9)");
  it.todo("BX62: the presentation selector matches real component files, and fails on an empty set (task 9)");

  // ADR-0018: a layer rule that matches no real file proves nothing. The components are .tsx files; if the
  // configuration stopped resolving that extension, the directory would still be scanned but nothing
  // would resolve into it, and every import of a component would be an unresolved name no rule matches.
  // One cruise of packages/ui/src serves the three layers: it is the slowest step of this file, and the
  // result is the same for each of them.
  let uiTree: CruiseResult | undefined;
  const uiTreeOnce = () => (uiTree ??= cruise(["packages/ui/src"]));

  it.each(LAYERS)("BX37: the real tree resolves imports into a component module of packages/ui/src/%s", (layer) => {
    const reached = uiTreeOnce().modules.filter(
      (module) =>
        module.source.startsWith(`packages/ui/src/${layer}/`) && module.source.endsWith(".tsx") && module.dependents.length > 0,
    );

    expect(reached).not.toEqual([]);
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
