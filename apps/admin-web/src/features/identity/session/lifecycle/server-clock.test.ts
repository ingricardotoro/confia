import { describe, expect, it } from "vitest";
import { createServerClock } from "./server-clock.ts";

// admin-web-shell-and-session-lifecycle, task 1.2 (SS137 to SS140, design.md H3). The clock keeps the last
// valid offset between the server and this tab and converts the server's instants with it. Every
// instant here is a fixed number: nothing reads the real clock.
const SERVER_NOW = Date.parse("2026-11-10T10:00:00Z");
const DEADLINE = "2026-11-10T10:30:00Z";
const MINUTE = 60_000;

describe("the server clock", () => {
  it("SS137: a browser clock 3 minutes ahead is corrected, so a deadline 30 minutes away stays 30 minutes away", () => {
    const clock = createServerClock();
    // The header names 10:00:00 (the server is at 10:00:00.500 on average) and the browser, 3 minutes ahead, read 10:03:00.500.
    clock.observe(SERVER_NOW, SERVER_NOW + 3 * MINUTE + 500);

    const local = clock.toLocal(DEADLINE);

    expect(local).toBe(SERVER_NOW + 33 * MINUTE);
    expect(local - (SERVER_NOW + 3 * MINUTE)).toBe(30 * MINUTE);
  });

  it("SS138: a browser clock 3 minutes behind is corrected the same way", () => {
    const clock = createServerClock();
    clock.observe(SERVER_NOW, SERVER_NOW - 3 * MINUTE + 500);

    const local = clock.toLocal(DEADLINE);

    expect(local).toBe(SERVER_NOW + 27 * MINUTE);
    expect(local - (SERVER_NOW - 3 * MINUTE)).toBe(30 * MINUTE);
  });

  it("a browser clock that agrees leaves only the half second that centres the header's second", () => {
    const clock = createServerClock();
    clock.observe(SERVER_NOW, SERVER_NOW + 500);

    expect(clock.toLocal(DEADLINE)).toBe(Date.parse(DEADLINE));
  });

  it("uses the formula (serverDate + 500) - receivedAt, whatever the pair", () => {
    const clock = createServerClock();
    clock.observe(1_000_000, 400_000);

    expect(clock.toLocal("1970-01-01T00:30:00Z")).toBe(1_199_500);
  });

  it("SS139: before any valid answer the offset is zero and the deadline is used as it comes", () => {
    const clock = createServerClock();

    expect(clock.toLocal(DEADLINE)).toBe(Date.parse(DEADLINE));
  });

  it("SS139: an answer with no Date leaves the offset at zero", () => {
    const clock = createServerClock();
    clock.observe(undefined, SERVER_NOW + 3 * MINUTE);

    expect(clock.toLocal(DEADLINE)).toBe(Date.parse(DEADLINE));
  });

  it("SS140: an answer with an invalid Date keeps the offset of the last valid one", () => {
    const clock = createServerClock();
    clock.observe(SERVER_NOW, SERVER_NOW + 3 * MINUTE + 500);
    const corrected = clock.toLocal(DEADLINE);

    clock.observe(undefined, SERVER_NOW + 10 * MINUTE);
    clock.observe(Number.NaN, SERVER_NOW + 11 * MINUTE);
    clock.observe(SERVER_NOW, Number.NaN);

    expect(corrected).toBe(SERVER_NOW + 33 * MINUTE);
    expect(clock.toLocal(DEADLINE)).toBe(corrected);
    expect(Number.isNaN(clock.toLocal(DEADLINE))).toBe(false);
  });

  it("a later valid answer replaces the offset", () => {
    const clock = createServerClock();
    clock.observe(SERVER_NOW, SERVER_NOW + 3 * MINUTE + 500);

    clock.observe(SERVER_NOW + MINUTE, SERVER_NOW + MINUTE - 2 * MINUTE + 500);

    expect(clock.toLocal(DEADLINE)).toBe(SERVER_NOW + 28 * MINUTE);
  });
});
