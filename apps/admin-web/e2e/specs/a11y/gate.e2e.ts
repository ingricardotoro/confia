import { checkCombination } from "../../support/a11y-gate.ts";
import { MATRIX, MINIMUM } from "../../support/a11y-matrix.ts";
import { assertCoversMinimum, assertNoBlocking, THEMES } from "../../support/a11y-report.ts";
import { recordResult } from "../../support/record-result.ts";
import { expect, test } from "../../support/scenario.ts";

// The accessibility gate over the rendered routes of the app, in both themes (build-integrity BX74, BX77, BX81).
// The same checkCombination serves the deliberate routes in deliberate-failures.e2e.ts.
test.describe("the accessibility gate over the real routes", () => {
  test("[BX77] refuses to pass on zero combinations, and the real matrix is not empty", () => {
    expect(() => assertCoversMinimum([], MINIMUM)).toThrow(/zero combinations/);
    expect(assertCoversMinimum(MATRIX, MINIMUM)).toHaveLength(MATRIX.length * THEMES.length);
  });

  for (const combination of MATRIX) {
    for (const theme of THEMES) {
      test(`[BX81] ${combination.route} [${combination.state}] in the ${theme} theme has no blocking violation`, async ({ page, scenarioId }) => {
        const result = await checkCombination(page, combination, theme, { scenarioId });
        recordResult(result);

        assertNoBlocking([result]);
      });
    }
  }
});
