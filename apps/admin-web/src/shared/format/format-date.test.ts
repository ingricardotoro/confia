import { describe, expect, it } from "vitest";
import { formatIsoDate } from "./format-date.ts";

// A date with no time keeps its day whatever the zone of the browser: it is formatted as UTC.
describe("formatIsoDate", () => {
  it("writes the date for es-HN, with the day, the month and the year of the one the server sent", () => {
    const text = formatIsoDate("2026-01-15");

    expect(text).toBe(new Intl.DateTimeFormat("es-HN", { dateStyle: "medium", timeZone: "UTC" }).format(Date.UTC(2026, 0, 15)));
    expect(text).toMatch(/15/);
    expect(text).toMatch(/2026/);
  });

  it("keeps the first and the last day of a year in that year, in any zone", () => {
    const original = process.env.TZ;
    try {
      for (const zone of ["America/Tegucigalpa", "Pacific/Kiritimati", "Pacific/Pago_Pago"]) {
        process.env.TZ = zone;
        expect(formatIsoDate("2026-01-01")).toMatch(/1.*2026/);
        expect(formatIsoDate("2026-12-31")).toMatch(/31.*2026/);
      }
    } finally {
      if (original === undefined) delete process.env.TZ;
      else process.env.TZ = original;
    }
  });

  it("takes another locale when it is given one", () => {
    expect(formatIsoDate("2026-01-15", "en-US")).toBe("Jan 15, 2026");
  });
});
