import { configDefaults, defineConfig } from "vitest/config";
import { confiaVitest } from "@confia/config/vitest";

// The same bar as packages/ui (build-integrity, BX97 to BX99): 80 percent, with every production
// file in the denominator, tested or not. Playwright journeys are *.e2e.ts and never match here. The unit
// tests of the simulated API and its test helpers live in e2e/ and are collected as *.test.ts (task 11).
export default defineConfig({
  test: {
    ...confiaVitest.test,
    include: ["src/**/*.test.{ts,tsx}", "*.test.ts", "e2e/**/*.test.ts"],
    exclude: [...configDefaults.exclude, "src/**/__fixtures__/**"],
    environment: "jsdom",
    // Several suites import the whole app cold or start real servers; with the default worker count a busy
    // machine turns that into 5 s timeouts in unrelated tests (staff sign in, tasks 1 and 3). Four is enough.
    maxWorkers: 4,
    setupFiles: ["./src/test/setup.ts"],
    passWithNoTests: false,
    coverage: {
      provider: "v8",
      include: ["src/**/*.{ts,tsx}"],
      exclude: ["src/**/*.test.{ts,tsx}", "src/**/*.test-d.ts", "src/**/__fixtures__/**", "src/test/**", "**/*.d.ts"],
      thresholds: { lines: 80, branches: 80, functions: 80, statements: 80 },
    },
  },
});
