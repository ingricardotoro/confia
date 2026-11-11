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

// admin-web-foundations-and-session, task 9.1 (part 9a; AW60, AW61, AW63). What this change must not
// deliver. Each of these stops being true on the day the change named in the scenario ships it.
describe("what this change leaves out", () => {
  const sources = filesUnder(join(PACKAGE_ROOT, "src")).filter((file) => /\.tsx?$/.test(file) && !/\.test(-d)?\.tsx?$/.test(file));

  it("AW60: no page header or breadcrumb exists in the app or in the entry point of the design system", () => {
    const shell = /\b(?:function|const|class|interface|type)\s+(?:Topbar|Sidebar|PageHeader|Breadcrumb)\b/;
    const entry = readFileSync(join(PACKAGE_ROOT, "..", "..", "packages", "ui", "src", "index.ts"), "utf8");

    expect(sources.length).toBeGreaterThan(0);
    expect(sources.filter((file) => shell.test(readFileSync(file, "utf8")))).toEqual([]);
    expect(entry).not.toMatch(/\b(?:Topbar|Sidebar|PageHeader|Breadcrumb)\b/);
  });

  it("AW61: the portal app does not exist", () => {
    expect(readdirSync(join(PACKAGE_ROOT, "..")).sort()).not.toContain("portal-web");
  });

  it("AW63: the production CSP is not delivered: no nginx configuration, and the only nonce in the app is the one the page reads", () => {
    const index = readFileSync(join(PACKAGE_ROOT, "index.html"), "utf8");

    expect(filesUnder(PACKAGE_ROOT).filter((file) => /nginx/i.test(file))).toEqual([]);
    // The server fills the placeholder per response (vite preview here, the application in production, change 11). The app
    // reads it once, in main.tsx, for the style element that Radix adds at run time; nothing else knows about a nonce.
    expect(index.match(/nonce/gi)).toEqual(["nonce", "NONCE"]);
    expect(index).toContain('<meta name="csp-nonce" content="__CSP_NONCE__" />');
    expect(
      sources.filter((file) => /nonce/i.test(readFileSync(file, "utf8"))).map((file) => file.replaceAll("\\", "/").split("/").pop()),
    ).toEqual(["main.tsx"]);
  });
});
