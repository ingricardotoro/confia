import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import tailwindcss from "@tailwindcss/vite";
import { defineBrowserCommand, playwright } from "@vitest/browser-playwright";
import { configDefaults, defineConfig } from "vitest/config";
import { confiaVitest } from "@confia/config/vitest";

// Two projects. "unit" runs in jsdom and is what `test` and CI's frontend job run, so a clean machine
// without a browser stays green. "a11y" is the accessibility gate (design.md decision 8): it runs the
// stories in real Chromium with the real stylesheet, and only `test:a11y` selects it.
const reportPath = join(import.meta.dirname, "reports", "a11y", "report.json");

export default defineConfig({
  test: {
    // Vitest's default API port (63315) falls in a range Windows reserves on some machines (listen
    // EACCES), so the port is set here: outside every excluded range seen so far, and overridable.
    api: { port: Number(process.env["CONFIA_VITEST_API_PORT"] ?? 51730) },
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
      thresholds: {
        lines: 80,
        branches: 80,
        functions: 80,
        statements: 80,
        // The only financial rule in the front end (design.md decision 12) carries its own bar.
        "src/format/**": { lines: 95, branches: 95, functions: 95, statements: 95 },
      },
    },
    passWithNoTests: false,
    projects: [
      {
        extends: true,
        test: {
          ...confiaVitest.test,
          name: "unit",
          // The a11y tests need a browser; the fixture trees hold deliberate offenders, not tests.
          exclude: [...configDefaults.exclude, "src/**/*.a11y.test.tsx", "src/**/__fixtures__/**"],
          environment: "jsdom",
          setupFiles: ["./src/test/setup.ts"],
        },
      },
      {
        extends: true,
        plugins: [tailwindcss()],
        test: {
          name: "a11y",
          include: ["src/**/*.a11y.test.tsx"],
          browser: {
            enabled: true,
            headless: true,
            provider: playwright(),
            instances: [{ browser: "chromium" }],
            commands: {
              // Written by Node because the browser cannot touch the disk; CI uploads the folder.
              writeA11yReport: defineBrowserCommand<[json: string]>((_context, json) => {
                mkdirSync(dirname(reportPath), { recursive: true });
                writeFileSync(reportPath, json);
              }),
              // vitest/browser has no media emulation; Playwright's page does.
              emulateColorScheme: defineBrowserCommand<[scheme: "light" | "dark"]>(async ({ page }, scheme) => {
                await page.emulateMedia({ colorScheme: scheme });
              }),
            },
          },
        },
      },
    ],
  },
});
