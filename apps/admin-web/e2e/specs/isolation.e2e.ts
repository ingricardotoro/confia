import { expect, openScenarioContext, recordedCalls, test } from "../support/scenario.ts";

// Isolation by header (design.md D15, R7): every context sends its own scenario to the simulated API, so
// tests that run at the same time never see each other. fullyParallel is on: the three tests below are
// scheduled together, and the first one also uses two contexts at once.
const readCurrent = (page: import("@playwright/test").Page) =>
  page.evaluate(() => fetch("/api/v1/auth/sessions/current").then((response) => response.status));

test.describe("scenario isolation", () => {
  test("two contexts of one test never share the calls the simulator records", async ({ browser, request }) => {
    const first = await openScenarioContext(browser, "iso-a-" + String(Date.now()));
    const second = await openScenarioContext(browser, "iso-b-" + String(Date.now()));
    try {
      await first.page.goto("/");
      await second.page.goto("/");
      expect(await readCurrent(first.page)).toBe(401);
      expect(await readCurrent(first.page)).toBe(401);
      expect(await readCurrent(second.page)).toBe(401);

      expect(await recordedCalls(request, first.id)).toHaveLength(2);
      expect(await recordedCalls(request, second.id)).toHaveLength(1);
    } finally {
      await first.context.close();
      await second.context.close();
    }
  });

  for (const reads of [1, 3]) {
    test(`a test that reads ${String(reads)} time(s) sees exactly its own calls`, async ({ page, request, scenarioId }) => {
      await page.goto("/");
      for (let index = 0; index < reads; index += 1) await readCurrent(page);

      const calls = await recordedCalls(request, scenarioId);
      expect(calls).toHaveLength(reads);
      expect(calls.every((call) => call.path === "/api/v1/auth/sessions/current")).toBe(true);
    });
  }
});
