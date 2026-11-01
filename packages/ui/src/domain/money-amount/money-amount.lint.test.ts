import { join } from "node:path";
import { ESLint } from "eslint";
import { describe, expect, it } from "vitest";

const UI_ROOT = join(import.meta.dirname, "..", "..", "..");
const MONEY_SCOPES = ["src/domain/money-amount", "src/format"];

// ADR-0018: a rule that evaluated nothing proves nothing, so the real code is linted with the package's
// own configuration and the evaluated set must hold the files that carry the money rules.
describe("the money scopes under the UI lint rules", () => {
  it("DS42, BX11: the real code lints without errors, over a non-empty set", async () => {
    const results = await new ESLint({ cwd: UI_ROOT }).lintFiles(MONEY_SCOPES);
    const evaluated = results.map((result) => result.filePath.replaceAll("\\", "/"));

    expect(evaluated.length).toBeGreaterThan(0);
    for (const file of ["src/domain/money-amount/MoneyAmount.tsx", "src/format/money.ts"]) {
      expect(evaluated.some((path) => path.endsWith(`/${file}`))).toBe(true);
    }
    const errors = results.flatMap((result) =>
      result.messages
        .filter((message) => message.severity === 2)
        .map((message) => [result.filePath, String(message.line), String(message.ruleId), message.message].join(" ")),
    );
    expect(errors).toEqual([]);
  }, 180_000);

  it.each([
    ["DS40, BX09: Number() on the amount", "export const total = (money: { amount: string }) => Number(money.amount);"],
    ["DS41, BX10: arithmetic on the amount", "export const total = (money: { amount: string }) => money.amount + 1;"],
  ])("%s keeps failing under the real configuration", async (_name, code) => {
    const eslint = new ESLint({ cwd: UI_ROOT });
    const [result] = await eslint.lintText(code, { filePath: join(UI_ROOT, "src/domain/money-amount/MoneyAmount.tsx") });
    const rules = (result?.messages ?? []).map((message) => message.ruleId);

    expect(rules).toContain("no-restricted-syntax");
  }, 60_000);
});
