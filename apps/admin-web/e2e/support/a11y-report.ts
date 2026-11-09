import type { Locator, Page } from "@playwright/test";
import type AxeBuilder from "@axe-core/playwright";

// The parts of the gate that need no browser, so Vitest covers them (a11y-report.test.ts).

/** One result of axe, as @axe-core/playwright returns it. */
export type Result = Awaited<ReturnType<AxeBuilder["analyze"]>>["violations"][number];

export const THEMES = ["light", "dark"] as const;
export type Theme = (typeof THEMES)[number];

export interface CombinationId {
  readonly route: string;
  readonly state: string;
}

/** A route and state the gate can reach: how to get there, and the content that proves it was reached. */
export interface GateCombination extends CombinationId {
  arrange(page: Page): Promise<void>;
  expected(page: Page): Locator;
}

export interface GateResult extends CombinationId {
  readonly theme: Theme;
  /** Critical and serious violations, and an incomplete color-contrast: they fail the build. */
  readonly blocking: Result[];
  /** Everything else axe found: uploaded and never failing (BX80). */
  readonly reported: Result[];
}

export interface ViolationSummary {
  readonly rule: string;
  readonly impact: string | null;
  readonly help: string;
  readonly targets: string[];
}

/** What is kept of a result: rule, impact and selectors, never the markup of the page. */
export interface RecordedResult extends CombinationId {
  readonly theme: Theme;
  readonly blocking: ViolationSummary[];
  readonly reported: ViolationSummary[];
}

const summarize = (violation: Result): ViolationSummary => ({
  rule: violation.id,
  impact: violation.impact ?? null,
  help: violation.help,
  targets: violation.nodes.map((node) => String(node.target)),
});

export const toRecorded = (result: GateResult): RecordedResult => ({
  route: result.route,
  state: result.state,
  theme: result.theme,
  blocking: result.blocking.map(summarize),
  reported: result.reported.map(summarize),
});

/** The evaluations the matrix produces: every combination in every theme. Zero fails (BX77). */
export function assertCoversMinimum(
  matrix: readonly CombinationId[],
  minimum: readonly CombinationId[],
): (CombinationId & { readonly theme: Theme })[] {
  if (matrix.length === 0) throw new Error("The accessibility gate has zero combinations to evaluate.");
  const missing = minimum.filter((wanted) => !matrix.some((c) => c.route === wanted.route && c.state === wanted.state));
  if (missing.length > 0) {
    throw new Error(`The matrix lacks: ${missing.map((c) => `${c.route} [${c.state}]`).join(", ")}.`);
  }
  return matrix.flatMap((c) => THEMES.map((theme) => ({ route: c.route, state: c.state, theme })));
}

/** One line per blocking violation, naming route, state, theme, rule and impact (BX75). */
export function describeBlocking(results: readonly GateResult[]): string {
  return results
    .flatMap((result) =>
      result.blocking.map(
        (violation) =>
          `${result.route} [${result.state}] ${result.theme}: ${violation.id} (${violation.impact ?? "unknown impact"})`,
      ),
    )
    .join("\n");
}

export function assertNoBlocking(results: readonly GateResult[]): void {
  const lines = describeBlocking(results);
  if (lines !== "") throw new Error(`Blocking accessibility violations:\n${lines}`);
}

export function summarizeReport(results: readonly RecordedResult[]) {
  return {
    evaluated: results.length,
    blocking: results.reduce((total, r) => total + r.blocking.length, 0),
    reported: results.reduce((total, r) => total + r.reported.length, 0),
    results,
  };
}
