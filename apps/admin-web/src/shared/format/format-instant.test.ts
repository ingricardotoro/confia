import { describe, expect, it } from "vitest";
import { formatInstant } from "./format-date.ts";

describe("formatInstant", () => {
  it("writes an instant in the zone of the institution, whatever the zone of the browser", () => {
    // 2026-11-02T15:30:00Z is 09:30 in Honduras (UTC-6, no daylight saving time).
    const text = formatInstant("2026-11-02T15:30:00Z");

    expect(text).toContain("2026");
    expect(text).toMatch(/9:30/);
  });

  it("reads an offset in the instant", () => {
    expect(formatInstant("2026-11-02T09:30:00-06:00")).toBe(formatInstant("2026-11-02T15:30:00Z"));
  });
});
