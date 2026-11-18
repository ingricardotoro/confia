import AxeBuilder from "@axe-core/playwright";
import type { Page } from "@playwright/test";
import { A11Y_TAGS, partitionViolations } from "@confia/config/a11y";
import type { GateCombination, GateResult, Theme } from "./a11y-report.ts";
import { MOCK_API_URL } from "./scenario-header.ts";

// One function for the real routes and for the deliberate ones (design.md D15). The policy is the one
// of @confia/config/a11y that the story gate of packages/ui imports too (BX73).

export interface CheckOptions {
  /** How long the expected content may take to show; a state that never shows it fails (BX78). */
  readonly expectedTimeoutMs?: number;
  /** The scenario of the test; required by a combination that names a preset of the simulated API. */
  readonly scenarioId?: string;
}

/**
 * Takes the page to the state, waits for the content that proves it was reached, and only then evaluates
 * it in the theme the system prefers. Analyzing a skeleton would be a false green, so a state that does not
 * show its content fails by the wait and is never analyzed.
 */
export async function checkCombination(
  page: Page,
  combination: GateCombination,
  theme: Theme,
  options: CheckOptions = {},
): Promise<GateResult> {
  const timeout = options.expectedTimeoutMs ?? 10_000;
  await page.emulateMedia({ colorScheme: theme });
  if (combination.preset !== undefined) {
    if (options.scenarioId === undefined) throw new Error(`${combination.route} [${combination.state}] names a preset and needs the scenario of the test.`);
    const applied = await page.request.post(`${MOCK_API_URL}/__control/scenarios/${options.scenarioId}`, { data: { preset: combination.preset } });
    if (!applied.ok()) throw new Error(`The simulator refused the preset of ${combination.route} [${combination.state}].`);
  }
  await combination.arrange(page);
  try {
    await combination.expected(page).waitFor({ state: "visible", timeout });
  } catch {
    throw new Error(
      `The expected content of ${combination.route} [${combination.state}] did not appear within ${String(timeout)} ms; the gate does not analyze a state it did not reach.`,
    );
  }
  if (combination.opensDialog === true) await isolateDialogFromPage(page);
  const outcome = await new AxeBuilder({ page }).withTags([...A11Y_TAGS]).analyze();
  return { route: combination.route, state: combination.state, theme, ...partitionViolations(outcome.violations, outcome.incomplete) };
}

/**
 * A modal dialog paints on an opaque panel, so nothing of the page behind it can change the contrast of its text. axe, however,
 * builds the stack of every line of a text from all the elements geometrically under it and compares the stacks of the lines:
 * a description of two lines that sits across the boundary of two rows of the table behind (the rows are compact now) has two
 * different stacks, and axe answers "partially overlaps other elements", which this gate counts as blocking. Radix already
 * makes the page behind inert and hidden from assistive technology while the dialog is open, so the gate hides it from the
 * visual stack the same way (CSSOM, which the CSP allows). The dialog content is analyzed in full, and the same route has its
 * own non-dialog states that analyze the page.
 */
async function isolateDialogFromPage(page: Page): Promise<void> {
  await page.evaluate(() => {
    for (const element of Array.from(document.body.children)) {
      if (element instanceof HTMLElement && element.querySelector('[role="dialog"]') === null && element.getAttribute("role") !== "dialog" && !element.hasAttribute("data-radix-focus-guard")) {
        element.style.setProperty("visibility", "hidden");
      }
    }
  });
}
