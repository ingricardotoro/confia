import { readFileSync } from "node:fs";
import { join, relative } from "node:path";
import { ESLint } from "eslint";
import { describe, expect, it } from "vitest";
import { appSourceRules, confiaEslint, uiSourceRules } from "@confia/config/eslint";

// admin-web-foundations-and-session, task 1.1 (design.md D2). Each control of the app rules has its
// deliberate violation and its accepted case, linted as inline code under the path that activates the
// scope. Same pattern as eslint-ui-rules.test.ts. The app root is the fixture mirror of
// apps/admin-web: it exists before the app does, and the scopes are relative to it.
const APP_ROOT = join(import.meta.dirname, "..", "dependency-fixtures", "apps", "admin-web");
const UI_ROOT = join(import.meta.dirname, "..", "..", "packages", "ui");

interface Finding {
  readonly ruleId: string | null;
  readonly message: string;
}

async function lint(
  code: string,
  filePath: string,
  { root = APP_ROOT, rules = appSourceRules() }: { root?: string; rules?: ReturnType<typeof appSourceRules> } = {},
): Promise<Finding[]> {
  const eslint = new ESLint({ cwd: root, overrideConfigFile: true, overrideConfig: rules });
  const [result] = await eslint.lintText(code, { filePath: join(root, filePath) });
  return result?.messages ?? [];
}

async function messagesOf(code: string, filePath: string, ruleId: string): Promise<string[]> {
  return (await lint(code, filePath)).filter((m) => m.ruleId === ruleId).map((m) => m.message);
}

const syntax = (code: string, filePath: string) => messagesOf(code, filePath, "no-restricted-syntax");
const globals = (code: string, filePath: string) => messagesOf(code, filePath, "no-restricted-globals");
const properties = (code: string, filePath: string) => messagesOf(code, filePath, "no-restricted-properties");

const COMPONENT = "src/features/identity/components/Bad.tsx";

describe("text in JSX (ADR-0011)", () => {
  it("BX38: rejects text between tags, naming the admin catalog hook", async () => {
    const found = await syntax("export const Bad = () => <button>Entrar</button>;", COMPONENT);
    expect(found).toEqual(["Hard-coded interface text. Resolve it from the es-HN catalog with useAdminText().t (ADR-0011)."]);
  });

  it("BX39: rejects a literal aria-label, naming the admin catalog hook", async () => {
    const found = await syntax('export const Bad = () => <button aria-label="Cerrar" />;', COMPONENT);
    expect(found).toEqual(["Hard-coded accessible text. Resolve it from the es-HN catalog with useAdminText().t (ADR-0011)."]);
  });

  it("accepts text that comes from the catalog", async () => {
    const code = 'export const Ok = ({ t }) => <button aria-label={t("common.close")}>{t("common.save")}</button>;';
    expect(await syntax(code, COMPONENT)).toEqual([]);
  });

  it.each(["src/features/identity/components/Bad.test.tsx", "src/features/identity/components/Bad.stories.tsx", "src/i18n/__fixtures__/bad.tsx"])(
    "accepts the same code in %s, which is outside the scope",
    async (filePath) => {
      expect(await syntax('export const Bad = () => <button aria-label="Cerrar">Entrar</button>;', filePath)).toEqual([]);
    },
  );
});

describe("manual number formatting", () => {
  it("BX40: rejects toFixed", async () => {
    const found = await syntax("export const text = (x: number) => x.toFixed(2);", "src/features/billing/format.ts");
    expect(found).toEqual([expect.stringMatching(/^Manual number formatting/)]);
  });

  it("BX41: rejects toLocaleString", async () => {
    const found = await syntax("export const text = (x: Date) => x.toLocaleString();", "src/features/billing/format.ts");
    expect(found).toEqual([expect.stringMatching(/^Manual number formatting/)]);
  });
});

describe("money is never converted or computed with (CLAUDE.md rules 1 and 3)", () => {
  const FILE = "src/features/billing/amount.ts";
  const MESSAGE = /^Money arithmetic or conversion/;
  const violations = [
    ["BX44", "Number()", "export const a = (money: { amount: string }) => Number(money.amount);"],
    ["BX44", "new Number()", "export const a = (money: { amount: string }) => new Number(money.amount);"],
    ["BX44", "parseFloat", "export const a = (money: { amount: string }) => parseFloat(money.amount);"],
    ["BX44", "parseInt", "export const a = (amount: string) => parseInt(amount, 10);"],
    ["BX44", "unary plus", "export const a = (money: { amount: string }) => +money.amount;"],
    ["BX44", "unary minus", "export const a = (money: { amount: string }) => -money.amount;"],
    ["BX44", "Math.round", "export const a = (totalAmount: number) => Math.round(totalAmount);"],
    ["BX45", "multiplication", "export const a = (money: { amount: string }) => money.amount * 2;"],
    ["BX45", "addition", "export const a = (money: { amount: string }) => 1 + money.amount;"],
    ["BX45", "subtraction", "export const a = (money: { amount: string }) => money.amount - 1;"],
    ["BX45", "division", "export const a = (money: { amount: string }) => money.amount / 2;"],
    ["BX45", "remainder", "export const a = (money: { amount: string }) => money.amount % 2;"],
    ["BX45", "exponentiation", "export const a = (money: { amount: string }) => money.amount ** 2;"],
    ["BX45", "compound assignment", "export let amount = 0; amount += 1;"],
    ["BX45", "division assignment", "export let amount = 8; amount /= 2;"],
    ["BX45", "increment", "export let amount = 0; amount++;"],
  ] as const;

  it.each(violations)("%s: rejects %s on an amount", async (_id, _name, code) => {
    const found = await syntax(code, FILE);
    expect(found).toHaveLength(1);
    expect(found[0]).toMatch(MESSAGE);
  });

  it.each([
    ["a countdown", "export const left = (seconds: number) => seconds - 1;"],
    ["a count converted", "export const n = (count: string) => Number(count);"],
    ["a comparison of two amounts", "export const same = (a: { amount: string }, b: { amount: string }) => a.amount === b.amount;"],
    ["an amount passed on", "export const shown = (money: { amount: string }) => format(money.amount);"],
    ["an amount in a template", "export const shown = (money: { amount: string }) => `(${money.amount})`;"],
    ["Math on something that is not an amount", "export const a = (n: number) => Math.round(n) + 1;"],
  ])("accepts %s: only an amount is protected in the app", async (_name, code) => {
    expect(await syntax(code, FILE)).toEqual([]);
  });

  it("does not report formatting twice: toFixed on an amount is the formatting control alone", async () => {
    const found = await syntax("export const a = (money: { amount: string }) => money.amount.toFixed(2);", FILE);
    expect(found).toEqual([expect.stringMatching(/^Manual number formatting/)]);
  });
});

describe("cookies are written by the server only", () => {
  it.each(["document.cookie = 'a=b';", "window.document.cookie = 'a=b';", "document.cookie += '; c=d';"])(
    "rejects %s",
    async (code) => {
      const found = await syntax(code, "src/shared/cookies.ts");
      expect(found).toEqual([expect.stringMatching(/^Cookie write/)]);
    },
  );

  it("accepts reading the cookie string", async () => {
    expect(await syntax("export const read = () => document.cookie;", "src/shared/cookies.ts")).toEqual([]);
  });
});

describe("queries in component tests", () => {
  it("rejects a structure query and a test id in a *.test.tsx file, and keeps the other controls", async () => {
    const code = 'container.querySelector("svg"); screen.getByTestId("total"); (1).toFixed(2);';
    const found = (await syntax(code, "src/features/identity/components/Bad.test.tsx")).map((m) => m.split(".")[0]);
    expect(found.sort()).toEqual(["Manual number formatting", "Query by structure or test id", "Query by structure or test id"]);
  });

  it("accepts the Testing Library queries by role and text", async () => {
    const code = 'screen.getByRole("button", { name: "Entrar" }); screen.getByText("Entrar");';
    expect(await syntax(code, "src/features/identity/components/Ok.test.tsx")).toEqual([]);
  });
});

describe("the network opens only from src/shared/api (BX49 to BX53, BX110)", () => {
  const FEATURE = "src/features/identity/load.ts";

  it.each([
    ["BX49", 'fetch("/api/v1/auth/session");', "fetch"],
    ["BX50", "new XMLHttpRequest();", "XMLHttpRequest"],
    ["BX51", 'new WebSocket("wss://example.test");', "WebSocket"],
    ["BX52", 'new EventSource("/events");', "EventSource"],
    ["BX110", 'new Worker("worker.js");', "Worker"],
    ["BX110", 'new SharedWorker("worker.js");', "SharedWorker"],
  ])("%s: rejects %s outside src/shared/api", async (_id, code, name) => {
    const found = await globals(code, FEATURE);
    expect(found).toEqual([expect.stringContaining(name)]);
    expect(found[0]).toContain("src/shared/api");
  });

  it.each([
    ["BX53", 'window.fetch("/x");', "fetch"],
    ["BX53", 'globalThis.fetch("/x");', "fetch"],
    ["BX53", 'self.fetch("/x");', "fetch"],
    ["BX53", 'new window.WebSocket("wss://example.test");', "WebSocket"],
    ["BX110", 'navigator.sendBeacon("/log", "x");', "sendBeacon"],
    ["BX110", 'window.navigator.sendBeacon("/log", "x");', "sendBeacon"],
  ])("%s: rejects %s outside src/shared/api", async (_id, code, name) => {
    const found = await properties(code, FEATURE);
    expect(found).toEqual([expect.stringContaining(name)]);
  });

  it("BX54: accepts every interface inside src/shared/api, at any depth", async () => {
    const code = 'fetch("/x"); new XMLHttpRequest(); window.fetch("/x"); navigator.sendBeacon("/x");';
    for (const filePath of ["src/shared/api/http.ts", "src/shared/api/deep/er/http.ts"]) {
      expect(await globals(code, filePath), filePath).toEqual([]);
      expect(await properties(code, filePath), filePath).toEqual([]);
    }
  });

  it("BX55: has no network rule in e2e, which uses the network by design", async () => {
    const code = 'fetch("/x"); new WebSocket("wss://example.test"); globalThis.fetch("/x");';
    expect(await globals(code, "e2e/journey.ts")).toEqual([]);
    expect(await properties(code, "e2e/journey.ts")).toEqual([]);
  });

  it("BX56: a folder with a similar name does not inherit the exception", async () => {
    const found = await globals('fetch("/x");', "src/shared/api-helpers/get.ts");
    expect(found).toEqual([expect.stringContaining("fetch")]);
  });

  it("applies to component files too", async () => {
    expect(await globals('fetch("/x");', COMPONENT)).toHaveLength(1);
  });
});

describe("the ui-only rules stay with packages/ui (BX47)", () => {
  it("rejects fetch in packages/ui with the ui message, and accepts it in src/shared/api of the app", async () => {
    const inUi = await lint('fetch("/x");', "src/atoms/spinner/a.ts", { root: UI_ROOT, rules: uiSourceRules() });
    expect(inUi.filter((m) => m.ruleId === "no-restricted-globals").map((m) => m.message)).toEqual([
      expect.stringContaining("packages/ui never makes requests"),
    ]);
    expect(await globals('fetch("/x");', "src/shared/api/http.ts")).toEqual([]);
  });

  it("gives the app neither the ui data-library ban nor its layer bans", async () => {
    const code = [
      'import { useQuery } from "@tanstack/react-query";',
      'import { Row } from "../../molecules/row/Row";',
      "export const q = [useQuery, Row];",
    ].join("\n");
    expect(await lint(code, "src/features/identity/query.ts")).toEqual([]);
  });

  it("gives the app the controls the two packages share: primitive tokens and hand-typed colours", async () => {
    expect(await syntax('export const c = "bg-blue-600";', "src/features/identity/a.ts")).toEqual([
      expect.stringMatching(/^Visual literal/),
    ]);
    expect(await syntax('export const c = "var(--palette-blue-600)";', "src/features/identity/a.ts")).toEqual([
      expect.stringMatching(/^Primitive token/),
    ]);
  });

  it("is wired by confiaEslint({ app: true }) with the exception in place, and not by default", async () => {
    const eslint = new ESLint({
      cwd: APP_ROOT,
      overrideConfigFile: true,
      overrideConfig: confiaEslint({ tsconfigRootDir: APP_ROOT, app: true }),
    });
    const rulesOf = async (path: string): Promise<Record<string, unknown>> => {
      const config = (await eslint.calculateConfigForFile(join(APP_ROOT, path))) as { rules?: Record<string, unknown> } | undefined;
      return config?.rules ?? {};
    };
    const names = (entry: unknown): string[] =>
      (Array.isArray(entry) ? entry.slice(1) : []).map((option) => (option as { name: string }).name);

    expect(names((await rulesOf("src/features/identity/load.ts"))["no-restricted-globals"])).toContain("Worker");
    expect((await rulesOf("src/shared/api/http.ts"))["no-restricted-globals"]).toBeUndefined();
    expect((await rulesOf("e2e/journey.ts"))["no-restricted-globals"]).toBeUndefined();

    const plain = new ESLint({
      cwd: APP_ROOT,
      overrideConfigFile: true,
      overrideConfig: confiaEslint({ tsconfigRootDir: APP_ROOT }),
    });
    const bare = (await plain.calculateConfigForFile(join(APP_ROOT, "src/features/identity/load.ts"))) as
      | { rules?: Record<string, unknown> }
      | undefined;
    expect(bare?.rules?.["no-restricted-globals"]).toBeUndefined();
  });

  it("keeps the portal ban on the administrative contract in the app rules", async () => {
    const code = 'import type { Money } from "@confia/contracts/admin";';
    const rules = appSourceRules({ portal: true });
    const found = (await lint(code, "src/features/identity/c.ts", { rules })).filter((m) => m.ruleId === "no-restricted-imports");
    expect(found.map((m) => m.message)).toEqual([expect.stringContaining("ADR-0003")]);
  });
});

// BX48: the separation does not weaken packages/ui. These run against the rules as they were before
// the split and must stay green through it; they are a characterization, not a RED step. The messages
// are the ones 13a fixed, written out in full.
describe("packages/ui keeps the rule and the message of every 13a violation (BX48)", () => {
  const ui = (code: string, filePath: string) => lint(code, filePath, { root: UI_ROOT, rules: uiSourceRules() });
  const only = async (code: string, filePath: string) => (await ui(code, filePath)).map((m) => [m.ruleId, m.message]);

  it.each([
    ["interface text", "export const Bad = () => <button>Guardar</button>;", "src/atoms/button/Bad.tsx",
      "Hard-coded interface text. Resolve it from the es-HN catalog with useUiText().t (ADR-0011)."],
    ["accessible text", 'export const Bad = () => <img alt="Cerrar" />;', "src/atoms/button/Bad.tsx",
      "Hard-coded accessible text. Resolve it from the es-HN catalog with useUiText().t (ADR-0011)."],
    ["manual formatting", "export const a = (x: number) => x.toFixed(2);", "src/atoms/amount/a.ts",
      "Manual number formatting. Format through src/format (Intl.NumberFormat), never toFixed or toLocaleString (ADR-0011)."],
    ["money arithmetic", "export const a = (m: { amount: string }) => Number(m.amount);", "src/format/a.ts",
      "Money arithmetic or conversion. An amount stays a string; the server computes and rounds it (CLAUDE.md rules 1 and 3)."],
    ["a primitive token", 'export const css = "var(--palette-blue-600)";', "src/molecules/card/a.ts",
      "Primitive token outside src/tokens. Components use the semantic and component tokens (design.md decision 6)."],
    ["a visual literal", 'export const c = "bg-blue-600";', "src/molecules/card/a.ts",
      "Visual literal. A colour is never typed by hand; use a semantic token or a utility that reads one (design.md decision 6)."],
  ] as const)("keeps %s", async (_name, code, filePath, message) => {
    expect(await only(code, filePath)).toEqual([["no-restricted-syntax", message]]);
  });

  it("keeps the query rule of component tests", async () => {
    expect(await only('container.querySelector("svg");', "src/atoms/button/Button.test.tsx")).toEqual([
      [
        "no-restricted-syntax",
        "Query by structure or test id. Find the element by role, label or text, the way a user does (design.md decision 7). Only *.a11y.test.tsx may reach elements directly.",
      ],
    ]);
  });

  it.each(["fetch", "XMLHttpRequest", "WebSocket", "EventSource"])("keeps the request ban on %s", async (name) => {
    const found = (await ui(`${name}("/x");`, "src/atoms/spinner/a.ts")).filter((m) => m.ruleId === "no-restricted-globals");
    expect(found.map((m) => m.message)).toEqual([
      `Unexpected use of '${name}'. packages/ui never makes requests; the container that owns the data does (CLAUDE.md, frontend).`,
    ]);
  });

  it("keeps the data library ban", async () => {
    const found = (await ui('import "axios";', "src/domain/money-amount/a.ts")).filter((m) => m.ruleId === "no-restricted-imports");
    expect(found.map((m) => m.message)).toEqual([
      "'axios' import is restricted from being used. packages/ui is pure presentation: it never requests or caches server data (ADR-0006).",
    ]);
  });
});

// ADR-0018: a rule that evaluated nothing proves nothing. The app has no source yet; the task that
// creates each kind of real file turns its entry into a test.
describe("the real apps/admin-web tree", () => {
  const REAL_ROOT = join(import.meta.dirname, "..", "..", "apps", "admin-web");
  const NETWORK_RULES = ["no-restricted-globals", "no-restricted-properties"];

  // Lints the production files of the real app with the app rules. Tests, stories and fixtures are the
  // rules' own exceptions, so they are left out the same way the app's eslint.config.js leaves them.
  async function lintRealTree() {
    const eslint = new ESLint({ cwd: REAL_ROOT, overrideConfigFile: true, overrideConfig: appSourceRules() });
    const results = await eslint.lintFiles(["src/**/*.{ts,tsx}"]);
    return results
      .map((result) => ({ file: relative(REAL_ROOT, result.filePath).replaceAll("\\", "/"), messages: result.messages }))
      .filter(({ file }) => !/\.(test|test-d)\.tsx?$|__fixtures__/.test(file));
  }

  const networkFindings = (tree: Awaited<ReturnType<typeof lintRealTree>>) =>
    tree.flatMap(({ file, messages }) =>
      messages.filter((m) => NETWORK_RULES.includes(m.ruleId ?? "")).map((m) => `${file}: ${m.message}`),
    );

  it("BX54: the network rule passes over the real client, which does call fetch", async () => {
    const tree = await lintRealTree();
    const client = tree.find(({ file }) => file === "src/shared/api/http.ts");

    expect(client, "src/shared/api/http.ts was linted").toBeDefined();
    expect(readFileSync(join(REAL_ROOT, "src/shared/api/http.ts"), "utf8")).toMatch(/\bfetch\s*\(/);
    expect(networkFindings(tree.filter(({ file }) => file.startsWith("src/shared/api/")))).toEqual([]);
  });

  it("BX57: the network rule evaluated real files inside and outside src/shared/api, and none broke it", async () => {
    const tree = await lintRealTree();
    const inside = tree.filter(({ file }) => file.startsWith("src/shared/api/"));
    const outside = tree.filter(({ file }) => !file.startsWith("src/shared/api/"));

    expect(inside.length).toBeGreaterThan(0);
    expect(outside.length).toBeGreaterThan(0);
    expect(networkFindings(tree)).toEqual([]);
  });

  it("BX46: the app rules pass over real component files, and over at least one real key of the admin catalog", async () => {
    const tree = await lintRealTree();
    const components = tree.filter(({ file }) => /\/components\/[^/]+\.tsx$/.test(file));
    const catalog = JSON.parse(readFileSync(join(REAL_ROOT, "src/i18n/locales/es-HN/admin.json"), "utf8")) as {
      signedIn: { title: string };
    };

    expect(components.length).toBeGreaterThan(0);
    expect(components.flatMap(({ file, messages }) => messages.map((m) => `${file}: ${m.ruleId ?? ""}: ${m.message}`))).toEqual([]);
    expect(catalog.signedIn.title).not.toBe("");
    expect(readFileSync(join(REAL_ROOT, "src/features/identity/signed-in/components/SignedInPage.tsx"), "utf8")).toContain("signedIn.title");
    expect(tree.flatMap(({ messages }) => messages)).toEqual([]);
  });
});
