import { defineConfig } from "vitest/config";
import { confiaVitest } from "@confia/config/vitest";

// The "unit" project runs in jsdom; the "a11y" browser project arrives with task 3.2.
export default defineConfig({
  test: {
    coverage: {
      provider: "v8",
      // Every source file counts in the denominator, tested or not (build-integrity, BX22 and BX24).
      include: ["src/**/*.{ts,tsx}"],
      exclude: [
        "src/**/*.test.{ts,tsx}",
        "src/**/*.test-d.ts",
        "src/**/*.stories.tsx",
        "src/**/__fixtures__/**",
        "src/test/**",
        "**/*.d.ts",
      ],
      thresholds: { lines: 80, branches: 80, functions: 80, statements: 80 },
    },
    passWithNoTests: false,
    projects: [
      {
        extends: true,
        test: {
          ...confiaVitest.test,
          name: "unit",
          environment: "jsdom",
          setupFiles: ["./src/test/setup.ts"],
        },
      },
    ],
  },
});
