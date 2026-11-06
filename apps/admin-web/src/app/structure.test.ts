import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

// admin-web-foundations-and-session, task 2.2. Facts about the package that no other test can see.
const PACKAGE_ROOT = join(import.meta.dirname, "..", "..");

function filesUnder(directory: string): string[] {
  return readdirSync(directory).flatMap((name) => {
    const path = join(directory, name);
    if (name === "node_modules" || name === "dist" || name === "coverage") return [];
    return statSync(path).isDirectory() ? filesUnder(path) : [path];
  });
}

describe("the shape of the package", () => {
  const manifest = JSON.parse(readFileSync(join(PACKAGE_ROOT, "package.json"), "utf8")) as {
    scripts: Record<string, string>;
    dependencies: Record<string, string>;
    devDependencies: Record<string, string>;
  };

  it("AW16: has no generated route tree and no step that would generate one", () => {
    const generated = filesUnder(PACKAGE_ROOT).filter((file) => /routeTree\.gen\./.test(file));

    expect(generated).toEqual([]);
    expect(manifest.scripts).not.toHaveProperty(["generate"]);
    expect(Object.keys({ ...manifest.dependencies, ...manifest.devDependencies })).not.toContain("@tanstack/router-plugin");
    expect(existsSync(join(PACKAGE_ROOT, "src", "routeTree.gen.ts"))).toBe(false);
  });

  it("AW62: reaches the API only through hand written code, so no orval client is a dependency", () => {
    expect(Object.keys(manifest.dependencies)).not.toContain("orval");
    expect(Object.keys(manifest.devDependencies)).not.toContain("orval");
  });
});
