import type { Page, Request } from "@playwright/test";
import { applyPreset } from "./organization.ts";
import { expect, seedSession } from "./scenario.ts";

export { applyPreset, bodyRows, dialog, field, firstColumn, openCreate, resultNotice, save, SAVE_TIMEOUT, test } from "./organization.ts";
export { filters, summary } from "./catalog.ts";

/** The path of the charge plans, and the seeded plans the journeys name (charges-plans cut). */
export const PLANS_PATH = "/charges/plans";
export const SEEDED_PLANS = {
  active: "00000000-0000-4000-8000-000000009001",
  draftEmpty: "00000000-0000-4000-8000-000000009002",
  archived: "00000000-0000-4000-8000-000000009003",
  draftWithItem: "00000000-0000-4000-8000-000000009004",
} as const;

/** Signs in with the seeded session, opens the plans from the navigation («Cobros» in Finanzas), with a preset already applied when one is given. */
export async function openPlans(page: Page, scenarioId: string, preset?: string): Promise<void> {
  if (preset !== undefined) await applyPreset(page, scenarioId, preset);
  await seedSession(page);
  await page.getByRole("navigation", { name: "Navegación principal" }).getByRole("link", { name: "Cobros" }).click();
  await expect(page).toHaveURL(/\/charges\/plans$/);
  await expect(page.getByRole("heading", { level: 1, name: "Cobros" })).toBeVisible();
  await expect(page.getByRole("heading", { level: 2, name: "Planes de cobro" })).toBeVisible();
}

/** Opens the detail of a seeded plan by its address, with a preset already applied when one is given. */
export async function openPlanDetail(page: Page, scenarioId: string, planId: string, name: string, preset?: string): Promise<void> {
  if (preset !== undefined) await applyPreset(page, scenarioId, preset);
  await seedSession(page);
  await page.goto(`${PLANS_PATH}/${planId}`);
  await expect(page.getByRole("heading", { level: 2, name })).toBeVisible({ timeout: 20_000 });
}

/** The query of the requests the page makes to the listing of plans, decoded, so `sort=name,desc` reads as it was sent. */
export function recordPlanQueries(page: Page): string[] {
  const seen: string[] = [];
  page.on("request", (request: Request) => {
    const url = new URL(request.url());
    if (request.method() === "GET" && url.pathname === "/api/v1/charges/plans") seen.push(decodeURIComponent(url.search));
  });
  return seen;
}

/** The rows of the table of items of a plan, as text. */
export async function itemRows(page: Page, tableName: string): Promise<string[][]> {
  return page
    // Hidden too: a dialog that is open hides the page behind it from the accessibility tree, and the table is still there.
    .getByRole("table", { name: tableName, includeHidden: true })
    .locator("tbody tr")
    .evaluateAll((rows) => rows.map((row) => [...row.querySelectorAll("td")].map((cell) => cell.textContent.trim())));
}
