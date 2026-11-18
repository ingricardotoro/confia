import { describe, expect, it } from "vitest";
import { intentFor, reasonLengthOf, reasonProblemOf, strippedReasonOf, VOID_REASON_MAX, VOID_REASON_MIN } from "./void-model.ts";

const OF = (length: number): string => "a".repeat(length);

describe("the reason of a void", () => {
  it("counts code points after stripping the border spaces, like the server", () => {
    expect(reasonLengthOf("   abc   ")).toBe(3);
    expect(reasonLengthOf("😀".repeat(4))).toBe(4);
    expect(strippedReasonOf("  motivo  ")).toBe("motivo");
  });

  it.each([
    [VOID_REASON_MIN - 1, "tooShort"],
    [VOID_REASON_MIN, undefined],
    [VOID_REASON_MAX, undefined],
    [VOID_REASON_MAX + 1, "tooLong"],
  ] as const)("a reason of %i characters is %s", (length, problem) => {
    expect(reasonProblemOf(OF(length))).toBe(problem);
  });

  it("does not count the spaces at the borders towards the minimum", () => {
    expect(reasonProblemOf(`   ${OF(VOID_REASON_MIN - 1)}   `)).toBe("tooShort");
  });

  it("refuses a line break or another control character, which the server refuses too", () => {
    expect(reasonProblemOf(`${OF(15)}\n${OF(15)}`)).toBe("control");
    expect(reasonProblemOf(`${OF(15)}${String.fromCodePoint(0x200b)}${OF(15)}`)).toBe("control");
  });
});

describe("the intent of a void", () => {
  it("keeps the key while the charge and the reason are the same, so a retry repeats the same request", () => {
    const first = intentFor(undefined, "c1", OF(25), () => "key-1");
    expect(intentFor(first, "c1", `${OF(25)}  `, () => "key-2")).toBe(first);
  });

  it("makes a new key when the reason or the charge changes", () => {
    const first = intentFor(undefined, "c1", OF(25), () => "key-1");
    expect(intentFor(first, "c1", OF(26), () => "key-2").idempotencyKey).toBe("key-2");
    expect(intentFor(first, "c2", OF(25), () => "key-3").idempotencyKey).toBe("key-3");
  });
});
