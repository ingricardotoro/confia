import { defineConfig } from "@playwright/test";

// admin-web-foundations-and-session, design.md D15 and D16. The journeys and the accessibility gate run in
// real Chromium against the production build served by `vite preview`, with the API simulated by
// e2e/mock-api (a simulation of the published shapes, not a test of the real stack).
//
// Isolation: every test sends its own scenario in the `x-confia-mock-scenario` header, so tests run in
// parallel without seeing each other (e2e/specs/isolation.e2e.ts). The written way out, if a shared state
// ever shows up, is `workers: 1`.
const CI = process.env["CI"] !== undefined;
const PAGE_URL = "http://localhost:5173";
const MOCK_API_URL = "http://localhost:8099";

export default defineConfig({
  testDir: "./e2e/specs",
  // The journeys are *.e2e.ts, not *.spec.ts: IdentityScopeExclusionInventoryTest (apps/api) fails on a
  // *.spec.ts under apps/ and the backend does not change here (D18).
  testMatch: /\.e2e\.ts$/,
  fullyParallel: true,
  forbidOnly: CI,
  retries: 0,
  outputDir: "reports/e2e/results",
  globalSetup: "./e2e/support/global-setup.ts",
  globalTeardown: "./e2e/support/global-teardown.ts",
  reporter: [["list"], ["html", { outputFolder: "reports/e2e/html", open: "never" }]],
  use: { baseURL: PAGE_URL, locale: "es-HN", trace: "retain-on-failure" },
  projects: [{ name: "chromium", use: { browserName: "chromium" } }],
  webServer: [
    {
      command: "node e2e/mock-api/start.mjs",
      url: `${MOCK_API_URL}/__control/health`,
      reuseExistingServer: !CI,
      timeout: 60_000,
    },
    {
      command: "vite build && vite preview",
      url: PAGE_URL,
      reuseExistingServer: !CI,
      timeout: 180_000,
      env: { CONFIA_API_PROXY_TARGET: MOCK_API_URL },
    },
  ],
});
