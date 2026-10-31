import { mkdirSync, mkdtempSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

/**
 * Builds `packages/ui/<directory>/cruise-probe.js` inside a fresh directory of the OS temporary
 * folder, hands its root to the callback and always removes it. Nothing under the repository is
 * created or deleted, so the probe cannot race the build and test tasks that own packages/ui/dist,
 * coverage and reports when Turborepo runs them in parallel (R3-probe-dir-race).
 */
export function withProbeTree<T>(directory: string, use: (root: string) => T): T {
  const root = realpathSync(mkdtempSync(join(tmpdir(), "confia-cruise-")));
  try {
    const folder = join(root, "packages", "ui", directory);
    mkdirSync(folder, { recursive: true });
    writeFileSync(join(folder, "cruise-probe.js"), "export const probe = 1;");
    return use(root);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}
