import { join } from "node:path";
import { ESLint } from "eslint";
import { describe, expect, it } from "vitest";
import { uiSourceRules } from "@confia/config/eslint";

// design-system-foundations-and-a11y, task 2.2 (design.md decision 7). Each control of the ADR-0011
// and design-token rules has its deliberate violation and its accepted case, linted as inline code
// under the file path that activates the scope. Same pattern as eslint-restrictions.test.ts.
const UI_ROOT = join(import.meta.dirname, "..", "..", "packages", "ui");

interface Finding {
  readonly ruleId: string | null;
  readonly message: string;
  readonly line: number;
  readonly column: number;
}

async function lint(code: string, filePath: string, options?: { portal: boolean }): Promise<Finding[]> {
  const eslint = new ESLint({
    cwd: UI_ROOT,
    overrideConfigFile: true,
    overrideConfig: uiSourceRules(options),
  });
  const [result] = await eslint.lintText(code, { filePath: join(UI_ROOT, filePath) });
  return result?.messages ?? [];
}

async function messagesOf(code: string, filePath: string, ruleId: string): Promise<string[]> {
  return (await lint(code, filePath)).filter((m) => m.ruleId === ruleId).map((m) => m.message);
}

const syntax = (code: string, filePath: string) => messagesOf(code, filePath, "no-restricted-syntax");

describe("text in JSX (ADR-0011)", () => {
  const BAD = "src/atoms/button/Bad.tsx";

  it("BX01: rejects text between tags", async () => {
    const found = await syntax("export const Bad = () => <button>Guardar</button>;", BAD);
    expect(found).toEqual([expect.stringContaining("Hard-coded interface text")]);
  });

  it("BX01: rejects a string expression used as a child", async () => {
    const found = await syntax('export const Bad = () => <p>{"Guardar"}</p>;', BAD);
    expect(found).toEqual([expect.stringContaining("Hard-coded interface text")]);
  });

  it.each(["aria-label", "aria-description", "title", "alt", "placeholder", "label"])(
    "BX02: rejects a literal %s",
    async (attribute) => {
      const found = await syntax(`export const Bad = () => <img ${attribute}="Cerrar" />;`, BAD);
      expect(found).toEqual([expect.stringContaining("Hard-coded accessible text")]);
    },
  );

  it("BX02: rejects a literal wrapped in an expression container", async () => {
    const found = await syntax('export const Bad = () => <button aria-label={"Cerrar"} />;', BAD);
    expect(found).toEqual([expect.stringContaining("Hard-coded accessible text")]);
  });

  it("accepts text that comes from the catalog", async () => {
    const code = 'export const Ok = ({ t }) => <button aria-label={t("common.actions.close")}>{t("common.actions.save")}</button>;';
    expect(await syntax(code, BAD)).toEqual([]);
  });

  it.each(["src/atoms/button/Button.stories.tsx", "src/atoms/button/Button.test.tsx"])(
    "accepts the same code in %s, which is outside the scope",
    async (filePath) => {
      const code = 'export const Story = () => <button aria-label="Cerrar">Guardar</button>;';
      expect(await syntax(code, filePath)).toEqual([]);
    },
  );
});

describe("text in JSX inside the money scopes", () => {
  it.each(["src/format/Bad.tsx", "src/domain/money-amount/Bad.tsx"])(
    "keeps both text controls on a component in %s",
    async (filePath) => {
      const code = 'export const Bad = () => <button aria-label="Cerrar">Guardar</button>;';
      const found = (await syntax(code, filePath)).sort();
      expect(found).toEqual([
        expect.stringContaining("Hard-coded accessible text"),
        expect.stringContaining("Hard-coded interface text"),
      ]);
    },
  );
});

describe("manual number formatting", () => {
  it("BX03: rejects toFixed outside src/format", async () => {
    const found = await syntax("export const text = (x: string) => x.toFixed(2);", "src/atoms/amount/a.ts");
    expect(found).toEqual([expect.stringContaining("Manual number formatting")]);
  });

  it("BX04: rejects toLocaleString outside src/format", async () => {
    const found = await syntax("export const text = (x: Date) => x.toLocaleString();", "src/molecules/row/a.ts");
    expect(found).toEqual([expect.stringContaining("Manual number formatting")]);
  });

  it("BX05: accepts Intl.NumberFormat inside src/format", async () => {
    const code = 'export const money = (v: string) => new Intl.NumberFormat("es-HN").format(v);';
    expect(await syntax(code, "src/format/money.ts")).toEqual([]);
  });
});

describe("money is never converted or computed with (CLAUDE.md rules 1 and 3)", () => {
  const MONEY_SCOPES = ["src/format/bad.ts", "src/domain/money-amount/bad.ts"];
  const arithmetic = [
    ["Number()", "export const a = (money: { amount: string }) => Number(money.amount);"],
    ["parseFloat", "export const a = (money: { amount: string }) => parseFloat(money.amount);"],
    ["parseInt", "export const a = (money: { amount: string }) => parseInt(money.amount, 10);"],
    ["Number.parseFloat", "export const a = (money: { amount: string }) => Number.parseFloat(money.amount);"],
    ["unary plus", "export const a = (money: { amount: string }) => +money.amount;"],
    ["Math.round", "export const a = (n: number) => Math.round(n);"],
    ["toFixed", "export const a = (n: number) => n.toFixed(2);"],
    ["addition", "export const a = (money: { amount: string }) => money.amount + 1;"],
    ["subtraction", "export const a = (money: { amount: string }) => money.amount - 1;"],
    ["multiplication", "export const a = (money: { amount: string }) => money.amount * 2;"],
    ["division", "export const a = (money: { amount: string }) => money.amount / 2;"],
    ["remainder", "export const a = (money: { amount: string }) => money.amount % 2;"],
    ["compound assignment", "export let a = 0; a += 1;"],
    ["new Number()", "export const a = (money: { amount: string }) => new Number(money.amount);"],
    ["toPrecision", "export const a = (n: number) => n.toPrecision(4);"],
    ["unary minus on a non-literal", "export const a = (money: { amount: string }) => -money.amount;"],
    ["increment", "export let a = 0; a++;"],
    ["exponentiation", "export const a = (money: { amount: string }) => money.amount ** 2;"],
    ["division assignment", "export let a = 8; a /= 2;"],
  ] as const;

  describe.each(MONEY_SCOPES)("in %s", (filePath) => {
    it.each(arithmetic)("BX09 BX10 DS40 DS41: rejects %s", async (_name, code) => {
      const found = await syntax(code, filePath);
      expect(found).toHaveLength(1);
      expect(found[0]).toMatch(/^Money arithmetic or conversion/);
    });

    it("accepts a template literal and RegExp.test", async () => {
      const code = "export const a = (s: string) => /^-?\\d+$/.test(s) ? `(${s})` : s;";
      expect(await syntax(code, filePath)).toEqual([]);
    });
  });

  it("does not apply outside the money scopes", async () => {
    expect(await syntax("export const a = (n: number) => Math.round(n) + 1;", "src/atoms/spinner/size.ts")).toEqual([]);
  });

  it("does not apply to tests inside the money scopes", async () => {
    expect(await syntax("export const a = Number('1') + 1;", "src/format/money.test.ts")).toEqual([]);
  });
});

describe("primitive tokens and visual literals (design.md decisions 6 and 7)", () => {
  const BAD = "src/molecules/card/bad.ts";
  const violations = [
    ["a --palette- reference", 'export const css = "var(--palette-blue-600)";'],
    ["a --palette- reference in a template", "export const css = `var(--palette-${n})`;"],
    ["a hexadecimal colour", 'export const c = "#2563EB";'],
    ["a short hexadecimal colour", 'export const c = "#fff";'],
    ["rgb()", 'export const c = "rgb(37, 99, 235)";'],
    ["hsl()", 'export const c = "hsl(221 83% 53%)";'],
    ["an arbitrary Tailwind colour", 'export const c = "bg-[#2563EB] p-2";'],
    ["a default palette variable", 'export const c = "var(--color-blue-600)";'],
    ["a default palette class", 'export const c = "bg-blue-600";'],
    ["a default palette text class", 'export const c = "p-2 text-red-500";'],
    ["a default palette class with a variant", 'export const c = "hover:dark:bg-slate-900/50";'],
  ] as const;

  it.each(violations)("DS04 DS05: rejects %s", async (_name, code) => {
    const found = await lint(code, BAD);
    const hits = found.filter((m) => m.ruleId === "no-restricted-syntax");
    expect(hits).toHaveLength(1);
    expect(hits[0]?.message).toMatch(/primitive|visual literal/i);
    // The finding points at the literal itself, on the line of the violation.
    expect(hits[0]?.line).toBe(1);
    expect(hits[0]?.column).toBe(code.search(/["`]/) + 1);
  });

  it.each([
    "bg-primary",
    "text-status-paid-fg",
    "bg-(--button-primary-bg)",
    "p-2 text-sm bg-primary-soft-bg hover:bg-primary-hover",
  ])("accepts the semantic classes %s", async (classes) => {
    expect(await syntax(`export const c = "${classes}";`, BAD)).toEqual([]);
  });

  it("keeps the formatting ban on token code but not on the token guard tests", async () => {
    const code = "export const ratio = (n: number) => n.toFixed(2);";
    expect(await syntax(code, "src/tokens/contrast.ts")).toHaveLength(1);
    expect(await syntax(code, "src/tokens/tokens.contrast.test.ts")).toEqual([]);
  });

  it.each(["src/tokens/index.css.ts", "src/atoms/button/__fixtures__/leak.ts"])(
    "exempts %s",
    async (filePath) => {
      expect(await syntax('export const c = "var(--palette-blue-600) #2563EB bg-blue-600";', filePath)).toEqual([]);
    },
  );
});

describe("requests (BX33)", () => {
  it.each([
    ['fetch("/api/v1/payments")', "fetch"],
    ["new XMLHttpRequest()", "XMLHttpRequest"],
    ['new WebSocket("wss://example.test")', "WebSocket"],
    ['new EventSource("/events")', "EventSource"],
  ])("rejects %s anywhere in packages/ui", async (code, name) => {
    for (const filePath of ["src/domain/money-amount/a.ts", "src/atoms/spinner/a.tsx", "vitest.config.ts"]) {
      const found = await messagesOf(code, filePath, "no-restricted-globals");
      expect(found, filePath).toEqual([expect.stringContaining(name)]);
    }
  });
});

describe("layers and data access, mirrored from dependency-cruiser (BX28 to BX34)", () => {
  const imports = (code: string, filePath: string) => messagesOf(code, filePath, "no-restricted-imports");

  it("BX28: atoms import neither molecules nor domain", async () => {
    expect(await imports('import { Row } from "../../molecules/row/Row";', "src/atoms/button/Bad.ts")).toHaveLength(1);
    expect(await imports('import { Money } from "../../domain/money-amount";', "src/atoms/button/Bad.ts")).toHaveLength(1);
  });

  it("BX29: molecules do not import domain", async () => {
    expect(await imports('import { Money } from "../../domain/money-amount";', "src/molecules/row/Bad.ts")).toHaveLength(1);
  });

  it("BX30: the allowed directions pass", async () => {
    const atom = 'import { Spinner } from "../../atoms/spinner";';
    expect(await imports(atom, "src/molecules/row/Ok.ts")).toEqual([]);
    expect(await imports(atom, "src/domain/money-amount/Ok.ts")).toEqual([]);
    expect(await imports('import { Row } from "../../molecules/row";', "src/domain/money-amount/Ok.ts")).toEqual([]);
    expect(await imports('import { cn } from "../../lib/cn";', "src/atoms/button/Ok.ts")).toEqual([]);
  });

  it("BX32: TanStack Query, axios, ky and swr are rejected anywhere in src", async () => {
    for (const specifier of ["@tanstack/react-query", "@tanstack/query-core", "axios", "ky", "swr"]) {
      const found = await imports(`import * as q from "${specifier}";`, "src/domain/money-amount/Bad.ts");
      expect(found, specifier).toHaveLength(1);
    }
  });

  it("BX34: a data library and an internal contract path give two errors, so the contract rule is not replaced", async () => {
    const code = [
      'import "@tanstack/react-query";',
      'import { Money } from "@confia/contracts/src/generated/portal/schemas.js";',
    ].join("\n");
    for (const filePath of ["src/atoms/button/Bad.ts", "src/molecules/row/Bad.ts", "src/domain/money-amount/Bad.ts", "src/lib/bad.ts"]) {
      expect(await imports(code, filePath), filePath).toHaveLength(2);
    }
  });

  it("keeps the portal ban on the administrative contract when the UI rules are composed with it", async () => {
    const code = 'import type { Money } from "@confia/contracts/admin";';
    const found = (await lint(code, "src/domain/money-amount/Bad.ts", { portal: true })).filter(
      (m) => m.ruleId === "no-restricted-imports",
    );
    expect(found.map((m) => m.message)).toEqual([expect.stringContaining("ADR-0003")]);
    expect(await imports(code, "src/domain/money-amount/Bad.ts")).toEqual([]);
  });

  it("accepts the public entry point of the contracts", async () => {
    const code = 'import type { Money } from "@confia/contracts/portal";';
    expect(await imports(code, "src/domain/money-amount/Ok.ts")).toEqual([]);
  });
});

describe("the real packages/ui tree", () => {
  it("BX08 BX11 DS42: lints without errors, over a non-empty file set that includes the formatter and the i18n modules", async () => {
    const eslint = new ESLint({ cwd: UI_ROOT });
    const results = await eslint.lintFiles(["."]);
    const evaluated = results.map((r) => r.filePath.replaceAll("\\", "/"));

    // ADR-0018: a rule that evaluated nothing proves nothing.
    expect(evaluated.length).toBeGreaterThan(0);
    expect(evaluated.some((p) => p.endsWith("/src/format/money.ts"))).toBe(true);
    expect(evaluated.filter((p) => /\/src\/i18n\/[^/]+\.ts$/.test(p)).length).toBeGreaterThan(0);

    const errors = results.flatMap((r) => r.messages.filter((m) => m.severity === 2).map((m) => [r.filePath, String(m.line), String(m.ruleId), m.message].join(" ")));
    expect(errors).toEqual([]);
  }, 180_000);
});
