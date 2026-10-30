import { readdirSync } from "node:fs";
import { join } from "node:path";

const SKIPPED_DIRECTORIES = new Set(["node_modules", "dist", "coverage", "reports", ".turbo"]);

/**
 * Lists every file under `root` whose name matches, as a path relative to `root` with forward
 * slashes. Dependency and build directories are skipped: the guards that use this look at what the
 * package authors, not at what was installed or generated.
 */
export function listFiles(root: string, matches: (name: string) => boolean): string[] {
  const found: string[] = [];
  const visit = (directory: string, prefix: string): void => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      if (entry.isDirectory()) {
        if (!SKIPPED_DIRECTORIES.has(entry.name)) visit(join(directory, entry.name), `${prefix}${entry.name}/`);
      } else if (matches(entry.name)) {
        found.push(`${prefix}${entry.name}`);
      }
    }
  };
  visit(root, "");
  return found.sort();
}
