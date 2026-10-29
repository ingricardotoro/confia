import { globSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { confiaVitest } from "@confia/config/vitest";

const packageRoot = join(import.meta.dirname, "..");
const normalize = (path: string) => path.replaceAll("\\", "/");

// build-integrity BX20: a test file the runner does not collect must fail the build instead of
// being skipped. If the shared include list lost "src/**/*.test.tsx", the only symptom would be
// missing tests, and the *.test.ts files that remain would keep the run green.
describe("the unit project's test collection", () => {
  const collected = new Set(
    confiaVitest.test.include?.flatMap((pattern) => globSync(pattern, { cwd: packageRoot })).map(normalize),
  );
  const onDisk = readdirSync(join(packageRoot, "src"), { recursive: true })
    .map((entry) => normalize(`src/${entry.toString()}`))
    .filter((path) => /\.test\.tsx?$/.test(path));

  it("evaluates a real set of test files", () => {
    expect(onDisk.length).toBeGreaterThan(0);
  });

  it("collects every *.test.ts and *.test.tsx file under src", () => {
    const skipped = onDisk.filter((path) => !collected.has(path));

    expect(skipped).toEqual([]);
  });
});
