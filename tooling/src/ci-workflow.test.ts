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
    expect(Object.keys(jobs)).toEqual(["backend", "backend-mutation", "frontend", "security", "design-system-accessibility"]);
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
