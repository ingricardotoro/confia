import { expect, recordedCalls, seedSession, test } from "../../support/scenario.ts";
import type { Page, Request } from "@playwright/test";

// The signed in page and the session in the browser (SS17, SS19, SS20, SS124 to SS126), against the
// simulated API. The state starts from the simulator's own seeded cookies.
const SIGN_OUT = { name: "Cerrar sesión" };
const API = /\/api\//;

/** Counts the requests to the API a page makes while `during` runs. */
async function apiRequestsDuring(page: Page, during: () => Promise<void>): Promise<string[]> {
  const seen: string[] = [];
  const listener = (request: Request) => {
    if (API.test(request.url())) seen.push(`${request.method()} ${new URL(request.url()).pathname}`);
  };
  page.on("request", listener);
  try {
    await during();
  } finally {
    page.off("request", listener);
  }
  return seen;
}

test.describe("the signed in page", () => {
  test("[SS17] two tabs restoring at once never overlap their refresh, and both end signed in", async ({ page, context }) => {
    await seedSession(page);
    const second = await context.newPage();
    await second.goto("/");
    await expect(second.getByRole("button", SIGN_OUT)).toBeVisible();
    // Concurrency as the browser sends it: a refresh is in flight from its request until it finishes or fails.
    let inFlight = 0;
    let maximum = 0;
    let refreshes = 0;
    const isRefresh = (request: Request) => new URL(request.url()).pathname === "/api/v1/auth/sessions/refresh";
    for (const tab of [page, second]) {
      tab.on("request", (request) => {
        if (!isRefresh(request)) return;
        inFlight += 1;
        refreshes += 1;
        maximum = Math.max(maximum, inFlight);
      });
      const finished = (request: Request) => {
        if (isRefresh(request)) inFlight -= 1;
      };
      tab.on("requestfinished", finished);
      tab.on("requestfailed", finished);
    }

    await Promise.all([page.reload(), second.reload()]);

    await expect(page.getByRole("button", SIGN_OUT)).toBeVisible();
    await expect(second.getByRole("button", SIGN_OUT)).toBeVisible();
    expect(refreshes).toBeGreaterThan(0);
    expect(maximum).toBe(1);
  });

  test("[SS19] makes no request while the browser clock moves 60 minutes", async ({ page }) => {
    await page.clock.install();
    await seedSession(page);
    await expect(page.getByRole("button", SIGN_OUT)).toBeVisible();

    const requests = await apiRequestsDuring(page, async () => {
      await page.clock.fastForward("01:00:00");
      await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => { resolve(undefined); })));
    });

    expect(requests).toEqual([]);
    await expect(page.getByRole("button", SIGN_OUT)).toBeVisible();
  });

  test("[SS20] makes no request when the window loses and regains focus, or the browser goes offline and online", async ({
    page,
    context,
    request,
    scenarioId,
  }) => {
    await seedSession(page);
    await expect(page.getByRole("button", SIGN_OUT)).toBeVisible();
    const before = (await recordedCalls(request, scenarioId)).length;

    const requests = await apiRequestsDuring(page, async () => {
      await page.evaluate(() => {
        for (const type of ["blur", "focus"]) window.dispatchEvent(new Event(type));
        Object.defineProperty(document, "visibilityState", { configurable: true, get: () => "hidden" });
        document.dispatchEvent(new Event("visibilitychange", { bubbles: true }));
        Object.defineProperty(document, "visibilityState", { configurable: true, get: () => "visible" });
        document.dispatchEvent(new Event("visibilitychange", { bubbles: true }));
      });
      await context.setOffline(true);
      await context.setOffline(false);
      await page.evaluate(() => new Promise((resolve) => setTimeout(resolve, 500)));
    });

    expect(requests).toEqual([]);
    expect((await recordedCalls(request, scenarioId)).length).toBe(before);
  });

  test("[SS124] stays on screen, with no personal data and no request, when time passes untouched", async ({ page }) => {
    await page.clock.install();
    await seedSession(page);
    await expect(page.getByRole("button", SIGN_OUT)).toBeVisible();

    const requests = await apiRequestsDuring(page, async () => {
      await page.clock.fastForward("08:00:00");
      await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => { resolve(undefined); })));
    });

    expect(requests).toEqual([]);
    await expect(page.getByRole("heading", { level: 1, name: "Sesión iniciada." })).toBeVisible();
    const text = await page.locator("body").innerText();
    expect(text).not.toMatch(/@|[0-9a-f]{8}-[0-9a-f]{4}-/i);
  });

  test("[SS125] has no dialog and no assertive region about the session expiring", async ({ page }) => {
    await seedSession(page);
    await expect(page.getByRole("button", SIGN_OUT)).toBeVisible();

    await expect(page.getByRole("dialog")).toHaveCount(0);
    await expect(page.getByRole("alertdialog")).toHaveCount(0);
    await expect(page.locator('[aria-live="assertive"]')).toHaveCount(0);
    expect(await page.content()).not.toContain("idleExpiresAt");
  });

  test("[SS126] keeps the other tab signed in after a sign out, and refreshes on no activity of the person", async ({ page, context }) => {
    await seedSession(page);
    const other = await context.newPage();
    await other.goto("/");
    await expect(other.getByRole("button", SIGN_OUT)).toBeVisible();

    await page.getByRole("button", SIGN_OUT).click();
    await expect(page.getByRole("heading", { level: 1, name: "Iniciar sesión" })).toBeVisible();
    const requests = await apiRequestsDuring(other, async () => {
      await other.keyboard.press("Shift");
      await other.mouse.move(40, 40);
      await other.mouse.click(60, 60);
      await other.evaluate(() => {
        document.dispatchEvent(new Event("touchstart", { bubbles: true }));
      });
      await other.evaluate(() => new Promise((resolve) => setTimeout(resolve, 500)));
    });

    expect(requests).toEqual([]);
    await expect(other.getByRole("button", SIGN_OUT)).toBeVisible();
  });
});
