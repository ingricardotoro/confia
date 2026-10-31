import { describe, expect, it } from "vitest";
import { focusRing } from "./focus-ring";

describe("focusRing", () => {
  it("draws a two pixel outline from the focus token for keyboard focus only", () => {
    expect(focusRing).toContain("focus-visible:outline-2");
    expect(focusRing).toContain("focus-visible:outline-border-focus");
  });

  it("never removes the outline without a replacement", () => {
    expect(focusRing).not.toContain("outline-none");
    expect(focusRing).not.toContain("outline-hidden");
  });
});
