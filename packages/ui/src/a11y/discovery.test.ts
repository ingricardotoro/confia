import { describe, expect, it } from "vitest";
import { assertStoriesDiscovered } from "./discovery";

// ADR-0018: a check that evaluates an empty set proves nothing, so it fails instead of passing [DS71, BX15].
describe("assertStoriesDiscovered", () => {
  it("throws when no story module was discovered", () => {
    expect(() => {
      assertStoriesDiscovered({});
    }).toThrow(/zero stories/i);
  });

  it("accepts a set with at least one module", () => {
    expect(() => {
      assertStoriesDiscovered({ "../atoms/spinner/Spinner.stories.tsx": {} });
    }).not.toThrow();
  });
});
