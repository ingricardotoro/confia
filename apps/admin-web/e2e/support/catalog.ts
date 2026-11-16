import type { Page, Request } from "@playwright/test";
import { applyPreset } from "./organization.ts";
import { expect, seedSession } from "./scenario.ts";

export { applyPreset, bodyRows, dialog, field, firstColumn, openCreate, resultNotice, save, SAVE_TIMEOUT, test } from "./organization.ts";

/** The path of the catalog: the payment concepts list. */
export const CATALOG_PATH = "/catalog";

/** Signs in with the seeded session, as the simulator's own route does, and opens the catalog from the navigation. */
export async function openCatalogPage(page: Page): Promise<void> {
  await seedSession(page);
  await page.getByRole("navigation", { name: "Navegación principal" }).getByRole("link", { name: "Catálogo" }).click();
  await expect(page).toHaveURL(/\/catalog$/);
  await expect(page.getByRole("heading", { level: 1, name: "Catálogo" })).toBeVisible();
  await expect(page.getByRole("heading", { level: 2, name: "Conceptos de pago" })).toBeVisible();
}

/** Opens the catalog with a preset already applied and the session seeded. */
export async function openCatalog(page: Page, scenarioId: string, preset?: string): Promise<void> {
  if (preset !== undefined) await applyPreset(page, scenarioId, preset);
  await openCatalogPage(page);
}

/** The query of the requests the page makes to the listing, decoded, so `sort=name,desc` reads as it was sent. */
export function recordCatalogQueries(page: Page): string[] {
  const seen: string[] = [];
  page.on("request", (request: Request) => {
    const url = new URL(request.url());
    if (request.method() === "GET" && url.pathname === "/api/v1/catalog/payment-concepts") seen.push(decodeURIComponent(url.search));
  });
  return seen;
}

export const summary = (page: Page) => page.getByRole("navigation", { name: "Paginación" });
export const filters = (page: Page) => page.getByRole("group", { name: "Filtros" });
