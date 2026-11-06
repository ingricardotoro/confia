import { describe, expect, it } from "vitest";
import config from "./vitest.config.ts";

// admin-web-foundations-and-session, task 2.2 (BX97 to BX99). The same bar as packages/ui: 80 percent,
// with every production file of the app in the denominator, tested or not.
describe("the coverage bar of the app", () => {
  const coverage = config.test?.coverage;

  it("BX97, BX98: fails below 80 percent on all four measures", () => {
    expect(coverage?.thresholds).toMatchObject({ lines: 80, branches: 80, functions: 80, statements: 80 });
  });

  it("BX99: counts every production file, so a file without tests lowers the figure", () => {
    expect(coverage?.include).toEqual(["src/**/*.{ts,tsx}"]);
    expect(coverage?.excludeAfterRemap).not.toBe(true);
    expect(coverage?.exclude).toEqual([
      "src/**/*.test.{ts,tsx}",
      "src/**/*.test-d.ts",
      "src/**/__fixtures__/**",
      "src/test/**",
      "**/*.d.ts",
    ]);
  });

  it("runs the test files in jsdom, outside the generated and fixture trees", () => {
    expect(config.test?.environment).toBe("jsdom");
    expect(config.test?.passWithNoTests).toBe(false);
  });
});
