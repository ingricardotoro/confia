import { describe, expect, it } from "vitest";
import { DEFAULT_API_PORT, parseApiPort } from "./api-port";

// CONFIA_VITEST_API_PORT picks the port of Vitest's API server (vitest.config.ts). A value that is
// set but unusable must stop the run with a clear error: falling back silently to Vitest's own
// default, 63315, is the port Windows reserves on some machines (listen EACCES).
describe("the Vitest API port", () => {
  it("uses the default when the variable is not set", () => {
    expect(parseApiPort(undefined)).toBe(DEFAULT_API_PORT);
    expect(DEFAULT_API_PORT).toBe(51730);
  });

  it.each([
    ["1", 1],
    ["51730", 51730],
    ["65535", 65535],
  ])("accepts %s", (raw, port) => {
    expect(parseApiPort(raw)).toBe(port);
  });

  it.each(["", " ", "0", "-1", "65536", "abc", "NaN", "1.5", "1e3", "0x10", "80 80"])(
    "rejects %j with an error that names the variable",
    (raw) => {
      expect(() => parseApiPort(raw)).toThrow(/CONFIA_VITEST_API_PORT.*1.*65535/);
    },
  );
});
