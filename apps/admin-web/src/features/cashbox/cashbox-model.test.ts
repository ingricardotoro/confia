import { describe, expect, it } from "vitest";
import { amountReasonOf, intentFor, noteReasonOf } from "./cashbox-model.ts";

describe("amountReasonOf", () => {
  it.each(["0", "0.00", "500", "500.5", "1234567890.99"])("accepts %s as a float or a count, judged on its characters", (amount) => {
    expect(amountReasonOf(amount, false)).toBeUndefined();
  });

  it.each([
    ["", "required"],
    ["12.345", "amount-format"],
    ["1,500.00", "amount-format"],
    ["abc", "amount-format"],
    ["-5", "amount-format"],
    ["12345678901", "amount-format"],
  ] as const)("refuses %j as a float or a count: %s", (amount, reason) => {
    expect(amountReasonOf(amount, false)).toBe(reason);
  });

  it("refuses a zero and a negative as the amount of a movement, and accepts a positive one", () => {
    expect(amountReasonOf("0", true)).toBe("amount-positive");
    expect(amountReasonOf("0.00", true)).toBe("amount-positive");
    expect(amountReasonOf("-1.00", true)).toBe("amount-positive");
    expect(amountReasonOf("0.01", true)).toBeUndefined();
  });
});

describe("noteReasonOf", () => {
  const required = { min: 20, max: 500, required: true } as const;

  it("asks for a required text, and lets an optional one be empty", () => {
    expect(noteReasonOf("   ", required)).toBe("required");
    expect(noteReasonOf("", { ...required, required: false })).toBeUndefined();
  });

  it("counts the characters the way the server does, stripped of border spaces and by code points", () => {
    expect(noteReasonOf(`  ${"a".repeat(19)}  `, required)).toBe("cash-reason-length");
    expect(noteReasonOf("a".repeat(20), required)).toBeUndefined();
    expect(noteReasonOf("a".repeat(501), required)).toBe("cash-reason-length");
    expect(noteReasonOf("😀".repeat(20), required)).toBeUndefined();
  });

  it("refuses a control character such as a line break", () => {
    expect(noteReasonOf(`${"a".repeat(20)}\n`.concat("b"), required)).toBe("cash-reason-control");
  });

  it("says too long, and nothing about a minimum, for a text that has none", () => {
    expect(noteReasonOf("a".repeat(501), { min: 1, max: 500, required: false })).toBe("too-long");
    expect(noteReasonOf("a", { min: 1, max: 500, required: false })).toBeUndefined();
  });
});

describe("intentFor", () => {
  it("keeps the key of the same request, so a retry answers the same write, and makes another for a request that changed", () => {
    const keys = ["k1", "k2"];
    const random = () => keys.shift() ?? "none";
    const first = intentFor(undefined, "open|500.00", random);
    const again = intentFor(first, "open|500.00", random);
    const changed = intentFor(again, "open|505.00", random);

    expect(again).toBe(first);
    expect(first.idempotencyKey).toBe("k1");
    expect(changed.idempotencyKey).toBe("k2");
  });

  it("makes a key with crypto when none is given", () => {
    expect(intentFor(undefined, "x").idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
  });
});
