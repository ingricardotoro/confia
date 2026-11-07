import AxeBuilder from "@axe-core/playwright";
import type { Page } from "@playwright/test";
import { A11Y_TAGS, partitionViolations } from "@confia/config/a11y";
import type { GateCombination, GateResult, Theme } from "./a11y-report.ts";

// One function for the real routes and for the deliberate ones (design.md D15). The policy is the one
// of @confia/config/a11y that the story gate of packages/ui imports too (BX73).

export interface CheckOptions {
  /** How long the expected content may take to show; a state that never shows it fails (BX78). */
  readonly expectedTimeoutMs?: number;
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
  await combination.arrange(page);
  try {
    await combination.expected(page).waitFor({ state: "visible", timeout });
  } catch {
    throw new Error(
      `The expected content of ${combination.route} [${combination.state}] did not appear within ${String(timeout)} ms; the gate does not analyze a state it did not reach.`,
    );
  }
  const outcome = await new AxeBuilder({ page }).withTags([...A11Y_TAGS]).analyze();
  return { route: combination.route, state: combination.state, theme, ...partitionViolations(outcome.violations, outcome.incomplete) };
}
