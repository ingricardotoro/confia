import type { Result, RunOptions } from "axe-core";

// The accessibility policy of @confia/ui (design.md decision 8, docs/ui-ux/03 section 11). The axe
// tags and the impacts that fail the build live here and nowhere else: the browser gate (task 3.2)
// and .storybook/preview.tsx both import them.

/** WCAG 2.0, 2.1 and 2.2 at levels A and AA. */
export const A11Y_TAGS = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"] as const;

/** A violation of one of these impacts fails the build. Moderate and minor are reported only. */
export const BLOCKING_IMPACTS = ["critical", "serious"] as const;

/**
 * The only impacts that are reported and do not fail the build. An allow-list, so an impact this
 * policy does not know (a new axe level, an empty string, null) fails closed instead of passing.
 */
export const REPORTED_IMPACTS = ["moderate", "minor"] as const;

/**
 * Rules whose `incomplete` result still blocks. axe reports `incomplete` when it could not decide;
 * for color-contrast that means it could not measure the text, so a pass would be a guess.
 */
export const BLOCKING_INCOMPLETE_RULES = ["color-contrast"] as const;

/** The options every axe run receives, so the gate and the Storybook panel evaluate the same rules. */
export const axeRunOptions: RunOptions = { runOnly: { type: "tag", values: [...A11Y_TAGS] } };

export interface PartitionedViolations {
  /** Results that fail the build. */
  readonly blocking: Result[];
  /** Results that are logged and uploaded but do not fail the build. */
  readonly reported: Result[];
}

// Anything that is not an explicitly reported impact blocks: critical and serious by name, and every
// impact axe did not assign or this policy does not recognize (undefined, null, any other string).
const isBlockingImpact = (impact: string | null | undefined): boolean =>
  impact === null || impact === undefined || !(REPORTED_IMPACTS as readonly string[]).includes(impact);

const isBlockingIncomplete = (result: Result): boolean =>
  (BLOCKING_INCOMPLETE_RULES as readonly string[]).includes(result.id);

/**
 * Splits an axe run in two by the policy. `violations` block by impact; `incomplete` results block
 * only for the rules in BLOCKING_INCOMPLETE_RULES and are otherwise reported, never ignored.
 */
export function partitionViolations(
  violations: readonly Result[],
  incomplete: readonly Result[] = [],
): PartitionedViolations {
  const blocking: Result[] = [];
  const reported: Result[] = [];
  for (const violation of violations) {
    (isBlockingImpact(violation.impact) ? blocking : reported).push(violation);
  }
  for (const unfinished of incomplete) {
    (isBlockingIncomplete(unfinished) ? blocking : reported).push(unfinished);
  }
  return { blocking, reported };
}
