import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { parse, parseAllDocuments } from "yaml";

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

// admin-web-foundations-and-session, task 2.2 (design.md D16 and D19). The admin web app arrives
// under the monorepo rules: versions from the catalog, a two week release age that pnpm enforces,
// a lockfile that covers the new importer, and the four tasks Turborepo runs on it.
interface Manifest {
  scripts?: Record<string, string>;
  dependencies?: Record<string, string>;
  devDependencies?: Record<string, string>;
}
interface LockImporter {
  dependencies?: Record<string, { specifier: string }>;
  devDependencies?: Record<string, { specifier: string }>;
}
interface Lockfile {
  importers: Record<string, LockImporter>;
  packages: Record<string, unknown>;
}

// pnpm 12 writes the lockfile as two YAML documents: the first pins pnpm itself, the last one is the
// workspace.
const readLockfile = () => parseAllDocuments(read("pnpm-lock.yaml")).at(-1)?.toJS() as Lockfile;
const manifestOf = (path: string) => JSON.parse(read(path)) as Manifest;
const sections = (manifest: Manifest) =>
  [manifest.dependencies ?? {}, manifest.devDependencies ?? {}] as const;

describe("the admin web package under the monorepo rules", () => {
  const workspace = parse(read("pnpm-workspace.yaml")) as {
    catalog: Record<string, string>;
  };
  const adminManifest = () => manifestOf("apps/admin-web/package.json");

  it("BX92: takes every version from the catalog or from the workspace", () => {
    const versions = sections(adminManifest()).flatMap((section) => Object.entries(section));

    expect(versions.length).toBeGreaterThan(0);
    for (const [name, version] of versions) {
      expect(["catalog:", "workspace:*"], `${name} must not carry a version of its own`).toContain(version);
      if (version === "catalog:") expect(workspace.catalog, `${name} is missing from the catalog`).toHaveProperty([name]);
    }
  });

  it("BX94: keeps the test libraries in devDependencies only", () => {
    const manifest = adminManifest();

    for (const name of ["@playwright/test", "@axe-core/playwright", "msw", "jsqr"]) {
      expect(manifest.dependencies ?? {}, `${name} must not ship to production`).not.toHaveProperty([name]);
    }
    expect(manifest.devDependencies).toHaveProperty(["@playwright/test"]);
    expect(manifest.devDependencies).toHaveProperty(["@axe-core/playwright"]);
  });

  it("D19: pins Playwright to the version of the browser package, exactly", () => {
    expect(workspace.catalog["@playwright/test"]).toBe("1.64.1");
    expect(workspace.catalog["@playwright/test"]).toBe(workspace.catalog["playwright"]);
  });

  it("BX91: records the importer in the lockfile with the specifiers of its manifest", () => {
    const lock = readLockfile();
    const importer = lock.importers["apps/admin-web"];
    const manifest = adminManifest();

    expect(importer).toBeDefined();
    const recorded = { ...importer?.dependencies, ...importer?.devDependencies };
    const declared = { ...manifest.dependencies, ...manifest.devDependencies };
    expect(Object.keys(recorded).sort()).toEqual(Object.keys(declared).sort());
    for (const [name, specifier] of Object.entries(declared)) {
      expect(recorded[name]?.specifier, name).toBe(specifier);
    }
  });

  it("D19: resolves react, react-dom and axe-core to one version each", () => {
    const lock = readLockfile();
    const versionsOf = (name: string) =>
      Object.keys(lock.packages).filter((key) => key.startsWith(`${name}@`));

    expect(versionsOf("react")).toHaveLength(1);
    expect(versionsOf("react-dom")).toHaveLength(1);
    expect(versionsOf("axe-core")).toEqual(["axe-core@4.14.0"]);
  });

  it("BX96: lets a high vulnerability fail the build, because the audit step cannot be bypassed", () => {
    const ci = parse(read(".github/workflows/ci.yml")) as {
      jobs: Record<string, { steps: { run?: string; "continue-on-error"?: unknown }[] }>;
    };
    const gate = Object.values(ci.jobs)
      .flatMap((job) => job.steps)
      .find((step) => step.run?.includes("pnpm audit --audit-level high"));

    expect(gate).toBeDefined();
    expect(gate?.["continue-on-error"]).toBeUndefined();
    expect(gate?.run).not.toMatch(/\|\||;\s*(true|exit 0)/);
  });

  it("AW01: runs lint, typecheck, test and build on the app, with no task left without a script", () => {
    const manifest = adminManifest();
    for (const task of ["lint", "typecheck", "test", "build"]) {
      expect(manifest.scripts, task).toHaveProperty([task]);
      const dryRun = spawnSync(
        "pnpm",
        ["turbo", "run", task, "--filter=@confia/admin-web", "--dry=json"],
        { cwd: repositoryRoot, encoding: "utf8", shell: true },
      );
      expect(dryRun.status, dryRun.stderr).toBe(0);
      const plan = JSON.parse(dryRun.stdout) as { tasks: { taskId: string; command: string }[] };
      const planned = plan.tasks.find((entry) => entry.taskId === `@confia/admin-web#${task}`);
      expect(planned, `turbo plans no ${task} task for the app`).toBeDefined();
      expect(planned?.command).not.toBe("<NONEXISTENT>");
    }
  });

  it("BX88: declares the dev task without cache, persistent, and leaves test:a11y as it was", () => {
    const turbo = JSON.parse(read("turbo.json")) as { tasks: Record<string, unknown> };

    expect(turbo.tasks["dev"]).toEqual({ cache: false, persistent: true, dependsOn: ["^generate"] });
    expect(turbo.tasks["test:a11y"]).toEqual({
      dependsOn: ["^generate", "generate"],
      outputs: ["reports/a11y/**"],
    });
  });

  it("AW62: keeps orval generating only Zod schemas, with no operations client", () => {
    const orval = read("packages/contracts/orval.config.ts");
    const clients = [...orval.matchAll(/\bclient:\s*"([^"]+)"/g)].map((match) => match[1]);

    expect(clients.length).toBeGreaterThan(0);
    expect(new Set(clients)).toEqual(new Set(["zod"]));
    expect(orval).not.toMatch(/\b(operationName|mock|tags-split|react-query|axios|fetch)\b/);
  });
});
