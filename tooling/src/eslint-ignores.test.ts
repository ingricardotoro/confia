import { join } from "node:path";
import { ESLint } from "eslint";
import { describe, expect, it } from "vitest";

// `turbo run lint test build` runs ESLint while `test --coverage` rewrites packages/ui/coverage and
// the build writes dist/. ESLint walks every folder it does not ignore, so a folder that vanishes
// mid-walk aborted the lint with ENOENT (task 3.3). Generated output is skipped without being
// walked: the folders below must stay in the shared ignore list.
const repositoryRoot = join(import.meta.dirname, "..", "..");

describe("what ESLint never walks", () => {
  const eslint = new ESLint({ cwd: join(repositoryRoot, "packages", "ui") });
  const ignored = (path: string) => eslint.isPathIgnored(join(repositoryRoot, "packages", "ui", path));

  it.each(["coverage/lcov-report/prettify.js", "coverage/src/i18n/index.js", "dist/storybook/assets/index.js", "reports/a11y/report.js"])(
    "ignores the generated output %s",
    async (path) => {
      expect(await ignored(path)).toBe(true);
    },
  );

  it("still lints the source", async () => {
    expect(await ignored("src/a11y/run-gate.ts")).toBe(false);
  });
});
