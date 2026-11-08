import { randomUUID } from "node:crypto";
import { expect, test as base, type APIRequestContext, type Browser, type BrowserContext, type Page } from "@playwright/test";
import { MOCK_API_URL, PAGE_URL, SCENARIO_HEADER } from "./scenario-header.ts";

export { expect, MOCK_API_URL, SCENARIO_HEADER };

/** A scenario id of the simulator: letters, digits and hyphens, unique per context. */
export const newScenarioId = (prefix = "e2e"): string => `${prefix}-${randomUUID().slice(0, 12)}`;

/**
 * The `test` of the journeys. Each test gets its own scenario id and its context sends it on every request
 * (the page, its fetches and the navigations), so the simulator keeps one session model per test.
 */
export const test = base.extend<{ scenarioId: string; cspViolations: string[] }>({
  // Filled by the page below; a test that provokes a violation on purpose empties it again.
  cspViolations: async ({ browserName }, use, testInfo) => {
    testInfo.annotations.push({ type: "browser", description: browserName });
    await use([]);
  },
  scenarioId: async ({ browserName }, use, testInfo) => {
    await use(newScenarioId(`${browserName}-${String(testInfo.workerIndex)}`));
  },
  context: async ({ context, scenarioId }, use) => {
    await context.setExtraHTTPHeaders({ [SCENARIO_HEADER]: scenarioId });
    await use(context);
  },
  // AW12: every journey runs with the CSP of vite preview, and a securitypolicyviolation event in any of
  // its documents fails it. The binding survives navigations, which a window variable would not.
  page: async ({ page, cspViolations }, use) => {
    await page.exposeFunction("confiaReportCsp", (text: string) => {
      cspViolations.push(text);
    });
    await page.addInitScript(() => {
      document.addEventListener("securitypolicyviolation", (event) => {
        const report = (window as unknown as { confiaReportCsp: (text: string) => Promise<void> }).confiaReportCsp;
        void report(`${event.violatedDirective} ${event.blockedURI}`);
      });
    });
    await use(page);
    expect(cspViolations, "CSP violations during the journey").toEqual([]);
  },
});

export interface ScenarioContext {
  readonly id: string;
  readonly context: BrowserContext;
  readonly page: Page;
}

/** A second context with its own scenario, for the tests that need two at once. */
export async function openScenarioContext(browser: Browser, id: string): Promise<ScenarioContext> {
  const context = await browser.newContext({ baseURL: PAGE_URL, extraHTTPHeaders: { [SCENARIO_HEADER]: id } });
  return { id, context, page: await context.newPage() };
}

/** The calls the simulator recorded for a scenario, through its control. */
export async function recordedCalls(
  request: APIRequestContext,
  id: string,
): Promise<readonly { readonly method: string; readonly path: string }[]> {
  const response = await request.get(`${MOCK_API_URL}/__control/requests/${id}`);
  expect(response.status()).toBe(200);
  return (await response.json()) as { method: string; path: string }[];
}

/** Leaves the two cookies of a signed in session in the browser and lands on the app, as the simulator's own route does. */
export async function seedSession(page: Page): Promise<void> {
  await page.goto(`${MOCK_API_URL}/__control/seed-session`);
}
