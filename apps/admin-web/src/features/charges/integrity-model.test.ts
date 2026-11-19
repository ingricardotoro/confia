import { describe, expect, it } from "vitest";
import { findingKindKeyOf, findingKindOf, KNOWN_FINDING_KINDS } from "./integrity-model.ts";

describe("the kinds of discrepancy", () => {
  it.each(KNOWN_FINDING_KINDS)("knows %s and has its own text", (kind) => {
    expect(findingKindOf(kind)).toBe(kind);
    expect(findingKindKeyOf(kind)).toBe(`charges.integrity.kinds.${kind}`);
  });

  it("reads a kind the server adds later as unknown, never as a blank", () => {
    expect(findingKindOf("SOMETHING_NEW")).toBe("unknown");
    expect(findingKindKeyOf("SOMETHING_NEW")).toBe("charges.integrity.kinds.unknown");
  });
});
