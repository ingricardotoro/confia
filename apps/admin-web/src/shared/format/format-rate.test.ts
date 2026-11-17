import { describe, expect, it } from "vitest";
import { formatRatePercent } from "./format-rate.ts";

describe("formatRatePercent", () => {
  it("keeps the four decimals the server sends", () => {
    expect(formatRatePercent("12.3400")).toBe("12.3400");
    expect(formatRatePercent("4.5000")).toBe("4.5000");
    expect(formatRatePercent("100.0000")).toBe("100.0000");
  });

  it("does not round a text the number type cannot hold exactly", () => {
    expect(formatRatePercent("12.3456")).toBe("12.3456");
    expect(formatRatePercent("0.0001")).toBe("0.0001");
  });
});
