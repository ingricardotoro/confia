import { existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, relative, sep } from "node:path";
import { describe, expect, it } from "vitest";
import { withProbeTree } from "./cruise-probe.js";

// R3-probe-dir-race: `turbo run lint typecheck test build` runs the tooling tests while @confia/ui
// builds dist/storybook and writes coverage/, so a probe must never create or delete a folder under
// packages/. The probe tree lives in the OS temporary directory instead.
const repositoryRoot = join(import.meta.dirname, "..", "..");
const sharedFolders = ["dist", "coverage", "reports"].map((name) => join(repositoryRoot, "packages", "ui", name));

describe("the generated-output probe tree", () => {
  it("lives in the OS temporary directory, outside the repository", () => {
    withProbeTree("dist", (root) => {
      expect(root.startsWith(tmpdir())).toBe(true);
      expect(relative(repositoryRoot, root).startsWith(`..${sep}`) || relative(repositoryRoot, root) === "..").toBe(true);
      expect(existsSync(join(root, "packages", "ui", "dist", "cruise-probe.js"))).toBe(true);
    });
  });

  it("is removed when the callback returns and when it throws", () => {
    let seen = "";
    withProbeTree("reports", (root) => {
      seen = root;
    });
    expect(existsSync(seen)).toBe(false);

    expect(() =>
      withProbeTree("coverage", (root) => {
        seen = root;
        throw new Error("boom");
      }),
    ).toThrow("boom");
    expect(existsSync(seen)).toBe(false);
  });

  it("leaves the shared output folders of packages/ui exactly as they were", () => {
    const before = sharedFolders.map((folder) => existsSync(folder));

    withProbeTree("dist", () => undefined);

    expect(sharedFolders.map((folder) => existsSync(folder))).toEqual(before);
  });
});
