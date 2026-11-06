import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { afterAll, describe, expect, it } from "vitest";
import { createTextKeyChecker, flattenCatalogKeys } from "./text-keys.js";

// admin-web-foundations-and-session, task 3.1 (BX42, BX43, AW26, AW27). The checker moved here from
// packages/ui so the admin app can use it too. These tests build a throwaway package on disk, so they
// pin the contract of the factory and not the layout of either application.
const roots: string[] = [];

function makePackage(files: Record<string, string>): string {
  const root = mkdtempSync(join(tmpdir(), "confia-text-keys-"));
  roots.push(root);
  const tsconfig = {
    compilerOptions: {
      target: "ES2023",
      lib: ["ES2023"],
      module: "ESNext",
      moduleResolution: "Bundler",
      strict: true,
      jsx: "preserve",
      types: [],
      noEmit: true,
    },
  };
  for (const [name, content] of Object.entries({ "tsconfig.json": JSON.stringify(tsconfig), ...files })) {
    mkdirSync(dirname(join(root, name)), { recursive: true });
    writeFileSync(join(root, name), content);
  }
  return root;
}

afterAll(() => {
  for (const root of roots) rmSync(root, { recursive: true, force: true });
});

describe("flattenCatalogKeys", () => {
  it("returns the dotted path of every leaf", () => {
    expect(flattenCatalogKeys({ a: "x", b: { c: "y", d: { e: "z" } } })).toEqual(["a", "b.c", "b.d.e"]);
  });

  it("returns nothing for an empty catalog", () => {
    expect(flattenCatalogKeys({})).toEqual([]);
  });
});

// A TypeScript program takes seconds, more under coverage and load.
describe("createTextKeyChecker", { timeout: 60_000 }, () => {
  it("collects each literal t() call with the file relative to the package root", () => {
    const root = makePackage({
      "src/a.tsx": 'export const a = [t("x.y"), i18n.t("p.q"), t(`plain.template`)];\n',
      "src/nested/b.tsx": 'export const b = t("n.m");\n',
    });

    const used = createTextKeyChecker(root).collectTextKeys();

    expect([...used].sort((l, r) => l.key.localeCompare(r.key))).toEqual([
      { key: "n.m", file: "src/nested/b.tsx" },
      { key: "p.q", file: "src/a.tsx" },
      { key: "plain.template", file: "src/a.tsx" },
      { key: "x.y", file: "src/a.tsx" },
    ]);
  });

  it("skips built keys, fixtures and files that are not tsx", () => {
    const root = makePackage({
      "src/a.tsx": "export const a = [t(`built.${part}`), t('a.' + part), t(variable)];\n",
      "src/__fixtures__/violation.tsx": 'export const f = t("fixture.key");\n',
      "src/plain.ts": 'export const p = t("plain.key");\n',
    });

    expect(createTextKeyChecker(root).collectTextKeys()).toEqual([]);
  });

  it("reports a type error in a virtual file under the package's own options, without writing it", () => {
    const root = makePackage({ "src/real.tsx": "export const real = 1;\n" });

    const diagnostics = createTextKeyChecker(root).checkVirtualFile(
      "src/virtual.tsx",
      'const value: number = "text";\nexport { value };\n',
    );

    expect(diagnostics).toHaveLength(1);
    expect(diagnostics[0]?.file).toBe("src/virtual.tsx");
    expect(diagnostics[0]?.message).toContain("string");
  });

  it("reports nothing for a virtual file that type-checks", () => {
    const root = makePackage({ "src/real.tsx": "export const real = 1;\n" });

    expect(
      createTextKeyChecker(root).checkVirtualFile("src/virtual.tsx", "export const value: number = 1;\n"),
    ).toEqual([]);
  });

  it("resolves a virtual file's imports against the package, as if it sat in src", () => {
    const root = makePackage({ "src/real.tsx": "export const real: number = 1;\n" });

    const diagnostics = createTextKeyChecker(root).checkVirtualFile(
      "src/virtual.tsx",
      'import { real } from "./real";\nexport const text: string = real;\n',
    );

    expect(diagnostics).toHaveLength(1);
    expect(diagnostics[0]?.message).toContain("number");
  });
});
