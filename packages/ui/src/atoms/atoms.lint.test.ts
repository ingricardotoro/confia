import { join } from "node:path";
import { ESLint } from "eslint";
import { describe, expect, it } from "vitest";

const UI_ROOT = join(import.meta.dirname, "..", "..");

// DS06 with real components: the UI rules of 2.2 (no text or accessible-attribute literals, no
// primitives or visual literals, no requests) run over the atoms of 4.1 and 4.2. ADR-0018: a rule that
// evaluated nothing proves nothing, so the set of evaluated files must include every component.
describe("the atoms of 4.1 and 4.2 under the UI lint rules", () => {
  it("DS06: lint without errors, over a non-empty set that includes each component", async () => {
    const eslint = new ESLint({ cwd: UI_ROOT });
    const results = await eslint.lintFiles(["src/atoms/button", "src/atoms/icon-button", "src/atoms/label", "src/atoms/badge", "src/atoms/separator", "src/atoms/skeleton", "src/atoms/input"]);
    const evaluated = results.map((r) => r.filePath.replaceAll("\\", "/"));

    expect(evaluated.length).toBeGreaterThan(0);
    for (const component of ["button/Button.tsx", "icon-button/IconButton.tsx", "label/Label.tsx", "badge/Badge.tsx", "separator/Separator.tsx", "skeleton/Skeleton.tsx", "input/Input.tsx"]) {
      expect(evaluated.some((p) => p.endsWith(`/src/atoms/${component}`))).toBe(true);
    }

    const errors = results.flatMap((r) =>
      r.messages.filter((m) => m.severity === 2).map((m) => [r.filePath, String(m.line), String(m.ruleId), m.message].join(" ")),
    );
    expect(errors).toEqual([]);
  }, 180_000);
});
