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

// admin-web-staff-sign-in, task 9.1 (SS05 static part, SS127, I307). What the access screens must not deliver, and what no test may
// claim. Each of these stops being true on the day the change named in its scenario ships what it forbids.
describe("what the staff sign in leaves out", () => {
  const sourcesOf = (directory: string) => filesUnder(directory).filter((file) => /\.tsx?$/.test(file) && !/\.test(-d)?\.tsx?$/.test(file));
  const sources = sourcesOf(join(PACKAGE_ROOT, "src"));
  const text = (file: string) => readFileSync(file, "utf8");

  it("SS05: no source of the app assigns document.cookie, so the only cookie it reads is the CSRF one", () => {
    const assignment = /document\s*\.\s*cookie\s*(?:=(?!=)|\+=)/;

    expect(sources.length).toBeGreaterThan(0);
    expect(sources.filter((file) => assignment.test(text(file)))).toEqual([]);
    expect(sources.filter((file) => /document\s*\.\s*cookie/.test(text(file))).map((file) => file.replaceAll("\\", "/").split("/src/")[1])).toEqual([
      "shared/api/csrf-cookie.ts",
    ]);
  });

  it("SS127: the API client has the six operations of access and no other, and the routes are the four screens", () => {
    const client = text(join(PACKAGE_ROOT, "src", "shared", "api", "auth-operations.ts"));
    const operations = [...client.matchAll(/^export (?:async )?function (\w+)/gm)].map((match) => match[1]);
    // A path is a literal, or the sessions path followed by a suffix.
    const literals = sources.flatMap((file) => [...text(file).matchAll(/"(\/api\/v1\/[^"]*)"/g)].map((match) => match[1]));
    const suffixes = sources.flatMap((file) => [...text(file).matchAll(/`\$\{SESSIONS\}(\/[^`]*)`/g)].map((match) => `/api/v1/auth/sessions${match[1] ?? ""}`));
    const paths = [...new Set([...literals, ...suffixes])].sort();

    expect(operations.sort()).toEqual(["completeSecondFactor", "endCurrentSession", "enrollSecondFactor", "getCurrentSession", "logIn", "refreshSession"]);
    expect(paths).toEqual(["/api/v1/auth/mfa/enrollments", "/api/v1/auth/mfa/verifications", "/api/v1/auth/sessions", "/api/v1/auth/sessions/current", "/api/v1/auth/sessions/refresh"]);
    expect(text(join(PACKAGE_ROOT, "src", "features", "identity", "routes.tsx"))).toMatch(
      /ACCESS_PATHS = \{\s*home: "\/",\s*signIn: "\/iniciar-sesion",\s*verification: "\/iniciar-sesion\/verificacion",\s*enrollment: "\/iniciar-sesion\/inscripcion",\s*\} as const/,
    );
  });

  it("SS127: nothing in the app names the first Super Admin, an administrative password reset or a voluntary enrollment or removal", () => {
    const forbidden = /first[-_ ]?super[-_ ]?admin|primer[-_ ]?super[-_ ]?admin|password[-_ ]?reset|reset[-_ ]?password|restablec\w*[-_ ]?contrase|(?:remove|disable|unenroll|delete)[-_ ]?(?:second[-_ ]?factor|mfa)|(?:baja|desactivar)[-_ ]?(?:del[-_ ])?(?:segundo[-_ ]?factor|mfa)/i;
    const catalog = text(join(PACKAGE_ROOT, "src", "i18n", "locales", "es-HN", "admin.json"));

    expect(sources.filter((file) => forbidden.test(text(file)))).toEqual([]);
    expect(forbidden.test(catalog)).toBe(false);
  });

  it("I307: no test names an origin other than the page and the simulated API, and the e2e run starts the simulator", () => {
    const tests = [...filesUnder(join(PACKAGE_ROOT, "e2e", "specs")), ...filesUnder(join(PACKAGE_ROOT, "e2e", "support"))].filter((file) => file.endsWith(".ts"));
    const origins = tests.flatMap((file) => [...text(file).matchAll(/https?:\/\/localhost:\d+/g)].map((match) => match[0]));
    const config = text(join(PACKAGE_ROOT, "playwright.config.ts"));

    expect(tests.length).toBeGreaterThan(0);
    expect([...new Set(origins)].sort()).toEqual(["http://localhost:5173", "http://localhost:8099"]);
    expect(tests.filter((file) => /__Secure-confia_admin_sid/.test(text(file)))).toEqual([]);
    expect(config).toContain('command: "node e2e/mock-api/start.mjs"');
    expect(config).toContain("CONFIA_API_PROXY_TARGET: MOCK_API_URL");
  });
});
