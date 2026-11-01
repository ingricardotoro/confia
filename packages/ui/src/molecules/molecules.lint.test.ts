import { join } from "node:path";
import { ESLint } from "eslint";
import { describe, expect, it } from "vitest";

const UI_ROOT = join(import.meta.dirname, "..", "..");

// DS06 with real molecules: the UI rules of 2.2 (no text or accessible-attribute literals, no
// primitives or visual literals, no requests) and the layer rule (a molecule never imports the
// domain) run over the molecules of 5.2. ADR-0018: a rule that evaluated nothing proves nothing, so
// the set of evaluated files must include every component.
describe("the molecules of 5.2 under the UI lint rules", () => {
  it("DS06: lint without errors, over a non-empty set that includes each component", async () => {
    const eslint = new ESLint({ cwd: UI_ROOT });
    const results = await eslint.lintFiles(["src/molecules"]);
    const evaluated = results.map((r) => r.filePath.replaceAll("\\", "/"));

    expect(evaluated.length).toBeGreaterThan(0);
    for (const component of ["form-field/FormField.tsx", "empty-state/EmptyState.tsx", "error-state/ErrorState.tsx"]) {
      expect(evaluated.some((p) => p.endsWith(`/src/molecules/${component}`))).toBe(true);
    }

    const errors = results.flatMap((r) =>
      r.messages.filter((m) => m.severity === 2).map((m) => [r.filePath, String(m.line), String(m.ruleId), m.message].join(" ")),
    );
    expect(errors).toEqual([]);
  }, 180_000);
});
