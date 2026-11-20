import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { describe, expect, it } from "vitest";

// The standard page header (docs/ui-ux/07, "Encabezado de página estándar"): a view inside the shell builds its heading
// with PageHeader, so a screen cannot drift into its own title markup. Sub-sections inside a view use level 3 or lower.
const SRC = join(import.meta.dirname, "..");

function sourceFiles(directory: string): string[] {
  return readdirSync(directory).flatMap((name) => {
    const path = join(directory, name);
    if (statSync(path).isDirectory()) return sourceFiles(path);
    return /\.tsx$/.test(name) && !/\.(test|stories)\.tsx$/.test(name) ? [path] : [];
  });
}

describe("page headers of the signed in screens", () => {
  // identity holds the sign in and enrollment screens, which have no shell and their own layout.
  const screens = sourceFiles(join(SRC, "features")).filter((file) => !relative(SRC, file).replaceAll("\\", "/").startsWith("features/identity/"));

  it("never writes a level 1 or level 2 heading by hand", () => {
    const offenders = screens.filter((file) => /<h[12][\s>]/.test(readFileSync(file, "utf8"))).map((file) => relative(SRC, file));

    expect(offenders).toEqual([]);
  });

  it("never hides a heading from sight to stand in for a visible header", () => {
    const offenders = screens.filter((file) => /<h\d[^>]*sr-only/.test(readFileSync(file, "utf8"))).map((file) => relative(SRC, file));

    expect(offenders).toEqual([]);
  });
});
