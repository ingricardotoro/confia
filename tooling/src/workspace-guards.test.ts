import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

// verify-report.md of frontend-monorepo-and-contracts-pipeline, WARNING-3. Four published scenarios
// were demonstrated once rather than tested on every build: a wrong Node version, a stale lockfile, a
// type error in CI and a high vulnerability in pnpm. Each rests on one setting, so this test pins
// those settings: removing any of them fails the build instead of silently disarming the scenario.
const repositoryRoot = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const read = (path: string) => readFileSync(join(repositoryRoot, path), "utf8");

describe("the settings the one-off demonstrations rely on", () => {
  const workspace = read("pnpm-workspace.yaml");
  const ci = read(".github/workflows/ci.yml");
  const rootManifest = JSON.parse(read("package.json")) as { engines?: { node?: string } };

  it("keep the Node version strict", () => {
    expect(workspace).toMatch(/^engineStrict: true$/m);
    expect(rootManifest.engines?.node).toBe(">=24 <25");
  });

  it("keep install scripts denied unless approved", () => {
    expect(workspace).toMatch(/^strictDepBuilds: true$/m);
  });

  it("keep CI installing from the frozen lockfile and typechecking", () => {
    expect(ci).toContain("pnpm install --frozen-lockfile");
    expect(ci).toMatch(/pnpm turbo run lint typecheck test build/);
  });

  it("keep CI blocking on high and critical vulnerabilities across the whole pnpm tree", () => {
    expect(ci).toContain("pnpm audit --audit-level high");
  });
});
