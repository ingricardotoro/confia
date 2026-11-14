import { describe, expect, it } from "vitest";
import { formatPercent } from "./format-percent.ts";

describe("formatPercent", () => {
  it.each([
    [60, "60.00"],
    [100, "100.00"],
    [33.33, "33.33"],
    [33.34, "33.34"],
    [0.01, "0.01"],
    [0, "0.00"],
    [99.99, "99.99"],
  ])("writes %s as %s, always with two decimals", (value, text) => {
    expect(formatPercent(value)).toBe(text);
  });
});
