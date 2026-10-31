import axe from "axe-core";
import type { Result } from "axe-core";
import { axeRunOptions, partitionViolations } from "./policy";

// The one function every story goes through, real or deliberately inaccessible (design.md decision
// 8): gate.a11y.test.tsx proves it rejects, stories.a11y.test.tsx applies it to the catalog.

/** The themes every story is checked in; the attribute value is the one docs/ui-ux/01 section 6 defines. */
export const THEMES = ["light", "dark"] as const;
export type Theme = (typeof THEMES)[number];

/** What the gate needs of a story composed with `composeStories`. */
export interface GateStory {
  readonly id: string;
  run(context?: { canvasElement?: HTMLElement; globals?: Record<string, unknown> }): Promise<void>;
}

export interface GateResult {
  readonly storyId: string;
  readonly theme: Theme;
  /** Critical and serious violations, and an incomplete color-contrast: they fail the build. */
  readonly blocking: Result[];
  /** Everything else axe found: logged and uploaded, never failing. */
  readonly reported: Result[];
}

/** Renders the story in the theme, waits for its `play`, and evaluates it with the tags of the policy. */
export async function checkStoryAccessibility(story: GateStory, theme: Theme): Promise<GateResult> {
  document.documentElement.dataset["theme"] = theme;
  const canvas = document.body.appendChild(document.createElement("div"));
  try {
    await story.run({ canvasElement: canvas, globals: { theme } });
    const outcome = await axe.run(canvas, axeRunOptions);
    return { storyId: story.id, theme, ...partitionViolations(outcome.violations, outcome.incomplete) };
  } finally {
    canvas.remove();
  }
}

export const checkStoryInAllThemes = async (story: GateStory): Promise<GateResult[]> => {
  const results: GateResult[] = [];
  for (const theme of THEMES) results.push(await checkStoryAccessibility(story, theme));
  return results;
};

/** One line per blocking violation, naming story, theme and rule; empty when nothing blocks. */
export function describeBlocking(results: readonly GateResult[]): string {
  return results
    .flatMap((result) =>
      result.blocking.map(
        (violation) => `${result.storyId} [${result.theme}]: ${violation.id} (${violation.impact ?? "unknown impact"})`,
      ),
    )
    .join("\n");
}

const describeViolation = (violation: Result) => ({
  rule: violation.id,
  impact: violation.impact ?? null,
  help: violation.help,
  targets: violation.nodes.map((node) => String(node.target)),
});

/** The JSON that CI uploads: blocking and reported findings per story and theme, minor ones included. */
export function summarize(results: readonly GateResult[]) {
  return results.map((result) => ({
    story: result.storyId,
    theme: result.theme,
    blocking: result.blocking.map(describeViolation),
    reported: result.reported.map(describeViolation),
  }));
}
