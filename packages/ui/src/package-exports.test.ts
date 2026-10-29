import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const packageRoot = join(import.meta.dirname, "..");
const manifest = JSON.parse(readFileSync(join(packageRoot, "package.json"), "utf8")) as {
  name: string;
  exports: Record<string, string>;
};

// design.md decision 1: the package publishes only the routes it lists. Tasks 1.3 and 2.1 add
// "./styles.css" and "./i18n" together with their tests; 6.3 asserts the final set of three.
describe("the @confia/ui exports map", () => {
  it("is the @confia/ui workspace package", () => {
    expect(manifest.name).toBe("@confia/ui");
  });

  it("lists exactly the public entry point", () => {
    expect(Object.keys(manifest.exports)).toEqual(["."]);
  });

  it("points every route at a file that exists", () => {
    for (const target of Object.values(manifest.exports)) {
      expect(existsSync(join(packageRoot, target)), target).toBe(true);
    }
  });
});
