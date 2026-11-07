import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { parse } from "yaml";

// design-system-foundations-and-a11y, design.md decision 8 and build-integrity BX14, BX17, BX18, BX19.
// The accessibility gate runs in Chromium, so it lives in a job of its own and the existing jobs must
// stay exactly as they were: a machine without a browser, and the `frontend verify` verdict, are
// unchanged. These tests pin both halves, and the pinning of every action by commit SHA.
const repositoryRoot = join(import.meta.dirname, "..", "..");
const readText = (path: string) => readFileSync(join(repositoryRoot, path), "utf8");

interface Step {
  name?: string;
  if?: string;
  uses?: string;
  run?: string;
  with?: Record<string, unknown>;
}
interface Job {
  name: string;
  "runs-on": string;
  "timeout-minutes"?: number;
  steps: Step[];
}
const workflow = parse(readText(".github/workflows/ci.yml")) as { jobs: Record<string, Job> };
const jobs = workflow.jobs;

// Summarised the way a reviewer reads a step: the action or the command, plus its inputs.
const summarize = (steps: Step[]) => steps.map(({ uses, run, with: inputs, if: condition }) => ({ uses, run, inputs, condition }));

const CHECKOUT = "actions/checkout@11d5960a326750d5838078e36cf38b85af677262";
const PNPM_SETUP = "pnpm/action-setup@ea17c68df8912ef543352723c149a84f56e3d413";
const SETUP_NODE = "actions/setup-node@820762786026740c76f36085b0efc47a31fe5020";
const UPLOAD = "actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02";

describe("the existing jobs of the CI workflow [BX19]", () => {
  it("keep their names and time limits", () => {
    expect(Object.entries(jobs).slice(0, 4).map(([key, job]) => [key, job.name, job["timeout-minutes"]])).toEqual([
      ["backend", "backend verify", 15],
      ["backend-mutation", "backend mutation", 75],
      ["frontend", "frontend verify", 10],
      ["security", "security scanning", undefined],
    ]);
  });

  it("keep the frontend steps, and its command, unchanged", () => {
    expect(summarize(jobs["frontend"]?.steps ?? [])).toEqual([
      { uses: CHECKOUT, inputs: { "persist-credentials": false } },
      { uses: PNPM_SETUP },
      { uses: SETUP_NODE, inputs: { "node-version-file": ".nvmrc", cache: "pnpm" } },
      { run: "pnpm install --frozen-lockfile" },
      { run: "pnpm turbo run lint typecheck test build --filter=!@confia/api" },
    ]);
  });

  it("keep the security job steps, and the audit gate, unchanged", () => {
    const steps = jobs["security"]?.steps ?? [];

    expect(steps).toHaveLength(7);
    expect(steps.at(-1)?.run).toBe("pnpm audit --audit-level high");
  });
});

describe("the design system accessibility job [BX14, BX17]", () => {
  const job = jobs["design-system-accessibility"];
  const frontendSteps = jobs["frontend"]?.steps ?? [];

  it("comes after the four existing jobs, with its own name and a 15 minute limit", () => {
    expect(Object.keys(jobs).slice(0, 5)).toEqual(["backend", "backend-mutation", "frontend", "security", "design-system-accessibility"]);
    expect(job?.name).toBe("design system accessibility");
    expect(job?.["runs-on"]).toBe("ubuntu-latest");
    expect(job?.["timeout-minutes"]).toBe(15);
  });

  it("checks out, sets up pnpm and Node with the same action SHAs as the frontend job", () => {
    const [checkout, pnpm, node] = job?.steps ?? [];

    expect(checkout).toEqual(frontendSteps[0]);
    expect(pnpm).toEqual(frontendSteps[1]);
    expect(node).toEqual(frontendSteps[2]);
    expect(checkout?.uses).toBe(CHECKOUT);
    expect(pnpm?.uses).toBe(PNPM_SETUP);
    expect(node?.uses).toBe(SETUP_NODE);
  });

  it("installs from the frozen lockfile, installs only Chromium, and runs the gate through Turborepo", () => {
    const commands = (job?.steps ?? []).flatMap((step) => (step.run === undefined ? [] : [step.run]));

    expect(commands).toEqual([
      "pnpm install --frozen-lockfile",
      "pnpm --filter @confia/ui exec playwright install --with-deps chromium",
      "pnpm turbo run test:a11y --filter=@confia/ui",
    ]);
  });

  it("uploads the report folder even when the gate fails", () => {
    const upload = job?.steps.at(-1);

    expect(upload?.uses).toBe(UPLOAD);
    expect(upload?.if).toBe("always()");
    expect(upload?.with?.["path"]).toBe("packages/ui/reports/a11y/");
  });
});

describe("every action of the workflow", () => {
  it("is pinned by a commit SHA of 40 hexadecimal characters", () => {
    const used = Object.values(jobs).flatMap((job) => job.steps.flatMap((step) => (step.uses === undefined ? [] : [step.uses])));

    expect(used.length).toBeGreaterThan(8);
    expect(used.filter((action) => !/^[\w.-]+\/[\w.-]+@[0-9a-f]{40}$/.test(action))).toEqual([]);
  });

  it("never persists the checkout credentials", () => {
    const checkouts = Object.values(jobs).flatMap((job) => job.steps.filter((step) => step.uses?.startsWith("actions/checkout@")));

    expect(checkouts.length).toBeGreaterThanOrEqual(5);
    expect(checkouts.filter((step) => step.with?.["persist-credentials"] !== false)).toEqual([]);
  });
});

describe("the Turborepo task test:a11y [BX17]", () => {
  const turbo = JSON.parse(readText("turbo.json")) as {
    tasks: Record<string, { dependsOn?: string[]; outputs?: string[]; cache?: boolean }>;
  };

  it("generates before it runs, and declares the report as its output", () => {
    expect(turbo.tasks["test:a11y"]?.dependsOn).toEqual(["^generate", "generate"]);
    expect(turbo.tasks["test:a11y"]?.outputs).toEqual(["reports/a11y/**"]);
  });

  it("is separate from test, so a machine without a browser keeps `test` green", () => {
    expect(turbo.tasks["test:a11y"]).not.toBe(turbo.tasks["test"]);
    expect(turbo.tasks["test"]?.outputs).toBeUndefined();
    expect(JSON.stringify(turbo.tasks["test"])).not.toContain("a11y");
  });
});

// admin-web-foundations-and-session, build-integrity BX83 to BX88 and BX90 (design.md D16). The job runs the
// journeys and the accessibility gate over rendered routes against the simulated API. It is added to the
// workflow and nothing that was there changes.
describe("the admin web e2e and accessibility job [BX83 to BX88, BX90]", () => {
  const job = jobs["admin-web-e2e"];
  const frontendSteps = jobs["frontend"]?.steps ?? [];

  it("comes after the five existing jobs, with its name, runner and a 20 minute limit [BX85]", () => {
    expect(Object.keys(jobs)).toEqual([
      "backend", "backend-mutation", "frontend", "security", "design-system-accessibility", "admin-web-e2e",
    ]);
    expect(job?.name).toBe("admin-web e2e and accessibility");
    expect(job?.["runs-on"]).toBe("ubuntu-latest");
    expect(job?.["timeout-minutes"]).toBe(20);
  });

  it("pins checkout, pnpm and Node to the SHAs of the frontend job [BX85]", () => {
    expect((job?.steps ?? []).slice(0, 3)).toEqual(frontendSteps.slice(0, 3));
  });

  it("installs Chromium and runs the e2e task through Turborepo, with vite preview behind Playwright [BX85]", () => {
    const commands = (job?.steps ?? []).flatMap((step) => (step.run === undefined ? [] : [step.run]));

    expect(commands).toEqual([
      "pnpm install --frozen-lockfile",
      "pnpm --filter @confia/admin-web exec playwright install --with-deps chromium",
      "pnpm turbo run test:e2e --filter=@confia/admin-web",
    ]);
    expect(readText("apps/admin-web/playwright.config.ts")).toContain('command: "vite build && vite preview"');
  });

  it("uploads the report folder as admin-web-e2e-report even when the job fails [BX85, BX86]", () => {
    const upload = job?.steps.at(-1);

    expect(upload?.uses).toBe(UPLOAD);
    expect(upload?.if).toBe("always()");
    expect(upload?.with).toMatchObject({ name: "admin-web-e2e-report", path: "apps/admin-web/reports/", "retention-days": 14 });
  });

  it("needs no Docker, no JDK and no real API [BX87]", () => {
    const text = JSON.stringify(job);

    expect(text).not.toMatch(/docker|java|jdk|maven|mvnw|testcontainers/i);
    expect(job?.steps.filter((step) => step.uses?.startsWith("actions/setup-java"))).toEqual([]);
  });

  it("leaves the frontend command exactly as it was [BX84]", () => {
    expect(frontendSteps.at(-1)?.run).toBe("pnpm turbo run lint typecheck test build --filter=!@confia/api");
  });

  it("keeps the design system job as it was [BX90]", () => {
    const steps = jobs["design-system-accessibility"]?.steps ?? [];

    expect(jobs["design-system-accessibility"]?.["timeout-minutes"]).toBe(15);
    expect(steps.map((step) => step.run ?? step.uses)).toEqual([
      CHECKOUT, PNPM_SETUP, SETUP_NODE, "pnpm install --frozen-lockfile",
      "pnpm --filter @confia/ui exec playwright install --with-deps chromium",
      "pnpm turbo run test:a11y --filter=@confia/ui", UPLOAD,
    ]);
  });
});

describe("the diff of ci.yml against main only adds [BX83]", () => {
  const git = (...args: string[]) => execFileSync("git", args, { cwd: repositoryRoot, encoding: "utf8" }).trim();
  const base = (() => {
    for (const ref of ["origin/main", "main"]) {
      try {
        return git("merge-base", "HEAD", ref);
      } catch {
        // The ref does not exist here (a shallow clone has no main): try the next one.
      }
    }
    return undefined;
  })();

  // Needs the history of main, which a shallow checkout of the CI job does not have; locally it always runs.
  it.skipIf(base === undefined)("has no deleted or modified line", () => {
    const [added, deleted] = git("diff", "--numstat", base ?? "", "--", ".github/workflows/ci.yml").split(/\s+/);

    expect(Number(added)).toBeGreaterThan(0);
    expect(deleted).toBe("0");
  });
});

describe("the Turborepo tasks test:e2e, dev and test:a11y [BX88]", () => {
  const turbo = JSON.parse(readText("turbo.json")) as { tasks: Record<string, Record<string, unknown>> };

  it("declares test:e2e uncached, generated first, with the report as its output and CI passed through", () => {
    expect(turbo.tasks["test:e2e"]).toEqual({
      cache: false,
      dependsOn: ["^generate", "generate"],
      outputs: ["reports/**"],
      passThroughEnv: ["CI"],
    });
  });

  it("declares dev, and leaves test:a11y as the design system change set it", () => {
    expect(turbo.tasks["dev"]).toEqual({ cache: false, persistent: true, dependsOn: ["^generate"] });
    expect(turbo.tasks["test:a11y"]).toEqual({
      dependsOn: ["^generate", "generate"],
      outputs: ["reports/a11y/**"],
    });
  });
});
