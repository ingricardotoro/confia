import type { Result } from "axe-core";
import { describe, expect, it } from "vitest";
import { A11Y_TAGS, BLOCKING_IMPACTS, axeRunOptions, partitionViolations } from "./policy";

// design.md decision 8: critical and serious fail the gate, moderate and minor are reported (03
// section 11). The tags and the impacts live in policy.ts and nowhere else.
// Older axe results carry `impact: null`, which the current typings no longer admit; the policy must survive it.
function result(id: string, impact: Result["impact"] | null): Result {
  return { id, impact, tags: [], description: id, help: id, helpUrl: "", nodes: [] } as unknown as Result;
}

describe("the accessibility policy", () => {
  it("evaluates exactly the WCAG 2.x A and AA tags, including 2.2 AA", () => {
    expect([...A11Y_TAGS]).toEqual(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"]);
  });

  it("blocks critical and serious impacts only", () => {
    expect([...BLOCKING_IMPACTS]).toEqual(["critical", "serious"]);
  });

  it("hands the same tags to axe as run options", () => {
    expect(axeRunOptions).toEqual({ runOnly: { type: "tag", values: [...A11Y_TAGS] } });
  });

  describe("partitionViolations", () => {
    it("blocks a critical and a serious violation", () => {
      const critical = result("image-alt", "critical");
      const serious = result("color-contrast", "serious");

      expect(partitionViolations([critical, serious])).toEqual({ blocking: [critical, serious], reported: [] });
    });

    it("only reports a moderate and a minor violation [DS69]", () => {
      const moderate = result("region", "moderate");
      const minor = result("tabindex", "minor");

      expect(partitionViolations([moderate, minor])).toEqual({ blocking: [], reported: [moderate, minor] });
    });

    it("splits a mixed list and keeps the order of each side", () => {
      const first = result("a", "serious");
      const second = result("b", "minor");
      const third = result("c", "critical");
      const fourth = result("d", "moderate");

      expect(partitionViolations([first, second, third, fourth])).toEqual({
        blocking: [first, third],
        reported: [second, fourth],
      });
    });

    it("blocks a violation whose impact is unknown instead of letting it through", () => {
      const unknown = result("mystery", null);
      const missing = result("absent", undefined);

      expect(partitionViolations([unknown, missing])).toEqual({ blocking: [unknown, missing], reported: [] });
    });

    it("finds nothing to block in an empty list", () => {
      expect(partitionViolations([])).toEqual({ blocking: [], reported: [] });
    });

    it("blocks an incomplete color-contrast result: it is never a pass (spec, Appendix B R-3)", () => {
      const incomplete = result("color-contrast", "serious");

      expect(partitionViolations([], [incomplete])).toEqual({ blocking: [incomplete], reported: [] });
    });

    it("blocks an incomplete color-contrast result whatever impact axe attached to it", () => {
      const incomplete = result("color-contrast", "minor");

      expect(partitionViolations([], [incomplete]).blocking).toEqual([incomplete]);
    });

    it("only reports an incomplete result of any other rule", () => {
      const incomplete = result("aria-valid-attr-value", "serious");

      expect(partitionViolations([], [incomplete])).toEqual({ blocking: [], reported: [incomplete] });
    });
  });
});
