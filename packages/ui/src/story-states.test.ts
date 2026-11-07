import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import * as moneyStories from "./domain/money-amount/MoneyAmount.stories";

// design-system, requirement "Cada componente tiene historias con sus estados": the states that apply
// to a component each have a story. The gate of axe checks every story; this checks that the states
// exist to be checked. A component that is itself a state (EmptyState, ErrorState) shows it in Default.
const read = (path: string) => readFileSync(join(import.meta.dirname, path), "utf8");
const storyNames = (path: string) =>
  [...read(path).matchAll(/^export const (\w+)\b/gm)].map((match) => match[1]);

// The Error state is told as Error, WithError or Invalid, depending on what the component calls it.
const ERROR = ["Error", "Invalid", "WithError"];
const APPLICABLE: [string, string, string[][]][] = [
  ["Button", "atoms/button/Button.stories.tsx", [["Loading"], ["Disabled"]]],
  ["Input", "atoms/input/Input.stories.tsx", [["Disabled"], ERROR]],
  ["Skeleton", "atoms/skeleton/Skeleton.stories.tsx", [["Loading"]]],
  ["EmptyState", "molecules/empty-state/EmptyState.stories.tsx", [["Empty", "Default"]]],
  ["ErrorState", "molecules/error-state/ErrorState.stories.tsx", [["Error", "Default"]]],
  // Alert has no loading or empty state: the one that applies is its error, the Destructive variant.
  ["Alert", "molecules/alert/Alert.stories.tsx", [["Destructive"]]],
  ["FormField", "molecules/form-field/FormField.stories.tsx", [["Disabled"], ERROR]],
  // The summary is itself the error state of a form; with no errors it renders nothing, which is DS79.
  ["FormErrorSummary", "molecules/form-error-summary/FormErrorSummary.stories.tsx", [["Default"]]],
];

describe("the stories of the states", () => {
  it.each(APPLICABLE)("DS63: %s has a story for each state that applies to it", (_name, path, states) => {
    const names = storyNames(path);

    for (const accepted of states) {
      expect(names.some((name) => name !== undefined && accepted.includes(name)), accepted.join(" or ")).toBe(true);
    }
  });

  it("DS95: Alert has a story per variant, with and without a title, and the summary has one, several and no-control", () => {
    const alert = storyNames("molecules/alert/Alert.stories.tsx");
    const summary = storyNames("molecules/form-error-summary/FormErrorSummary.stories.tsx");

    expect(alert).toEqual(expect.arrayContaining(["Info", "Warning", "Destructive", "Default", "WithoutTitle"]));
    expect(summary).toEqual(expect.arrayContaining(["Default", "Several", "WithoutControl"]));
  });

  it("DS64: MoneyAmount has stories for zero, a negative, a large amount, HNL and USD", () => {
    const { Usd, Zero, Negative, Large } = moneyStories;

    expect(moneyStories.default.args.value.currency).toBe("HNL");
    expect(Usd.args?.value?.currency).toBe("USD");
    expect(Zero.args?.value?.amount).toBe("0.00");
    expect(Negative.args?.value?.amount).toMatch(/^-\d/);
    expect(Large.args?.value?.amount.replace(/\D/g, "").length).toBeGreaterThanOrEqual(12);
  });
});
