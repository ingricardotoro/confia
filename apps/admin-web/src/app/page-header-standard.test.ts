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

  it("builds the heading of each payments view with PageHeader at level 2, under the level 1 of the area, with the breadcrumb of a detail (and the standard BackLink while it is unknown)", () => {
    const views = ["PaymentsView", "RegisterPaymentView", "PaymentDetailView"].map((name) => readFileSync(join(SRC, "features", "payments", "components", `${name}.tsx`), "utf8"));

    for (const source of views) expect(source).toMatch(/<PageHeader[\s\S]*?headingLevel=\{2\}/);
    expect(views[2]).toContain("<Breadcrumb");
    expect(views[2]).toContain("<BackLink");
    expect(readFileSync(join(SRC, "features", "payments", "PaymentsAreaContainer.tsx"), "utf8")).toContain("<PageContent");
  });

  it("builds the heading of each cashbox view with PageHeader at level 2, under the level 1 of the area, with the breadcrumb of a detail (and the standard BackLink while it is unknown)", () => {
    const views = ["MyCashboxView", "CashSessionsView", "CashSessionDetailView"].map((name) => readFileSync(join(SRC, "features", "cashbox", "components", `${name}.tsx`), "utf8"));

    for (const source of views) expect(source).toMatch(/<PageHeader[\s\S]*?headingLevel=\{2\}/);
    expect(views[2]).toContain("<Breadcrumb");
    expect(views[2]).toContain("<BackLink");
    expect(readFileSync(join(SRC, "features", "cashbox", "CashboxContainer.tsx"), "utf8")).toContain("<PageContent");
  });
});
