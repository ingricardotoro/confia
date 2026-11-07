import { checkCombination } from "../../support/a11y-gate.ts";
import { assertNoBlocking, type GateResult, type Theme } from "../../support/a11y-report.ts";
import { fixtureCombination } from "../../support/fixtures.ts";
import { expect, test } from "../../support/scenario.ts";

// The routes that exist only here prove the gate evaluates and fails (build-integrity BX76 to BX80). They are
// HTML files served by page.route from the same origin: no route in the app, no build flag (AW15).
const evaluate = (page: Parameters<typeof checkCombination>[0], name: Parameters<typeof fixtureCombination>[0], theme: Theme) =>
  checkCombination(page, fixtureCombination(name), theme);

test.describe("the gate fails on the deliberate routes", () => {
  test("[BX76] a control with no accessible name is a blocking violation, named by rule and impact", async ({ page }) => {
    const result = await evaluate(page, "inaccessible", "light");

    expect(result.blocking.map((violation) => violation.id)).toContain("button-name");
    expect(() => {
      assertNoBlocking([result]);
    }).toThrow(
      "/__a11y-fixtures__/inaccessible.html [ready] light: button-name (critical)",
    );
  });

  test("[BX79] a contrast that fails only in the dark theme fails, because both themes are evaluated", async ({ page }) => {
    const results: GateResult[] = [];
    for (const theme of ["light", "dark"] as const) results.push(await evaluate(page, "dark-only-contrast", theme));

    expect(results.map((result) => [result.theme, result.blocking.map((violation) => violation.id)])).toEqual([
      ["light", []],
      ["dark", ["color-contrast"]],
    ]);
    expect(() => {
      assertNoBlocking(results);
    }).toThrow("[ready] dark: color-contrast");
  });

  test("[BX80] a moderate violation is reported and does not fail", async ({ page }) => {
    const result = await evaluate(page, "moderate-only", "light");

    expect(result.blocking).toEqual([]);
    expect(result.reported.map((violation) => violation.impact)).toContain("moderate");
    expect(() => {
      assertNoBlocking([result]);
    }).not.toThrow();
  });

  test("[BX78] a state that never shows its content fails by that wait and is not analyzed", async ({ page }) => {
    test.setTimeout(30_000);

    await expect(checkCombination(page, fixtureCombination("stuck-loading"), "light", { expectedTimeoutMs: 2_000 })).rejects.toThrow(
      /expected content .* did not appear/,
    );
  });
});
