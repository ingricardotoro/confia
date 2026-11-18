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

/** The path of the price lists, and the seeded lists the journeys name (catalog-price-lists cut). */
export const PRICE_LISTS_PATH = "/catalog/price-lists";
export const SEEDED_LISTS = {
  institution2026: "00000000-0000-4000-8000-000000009001",
  first2026: "00000000-0000-4000-8000-000000009002",
  archived2025: "00000000-0000-4000-8000-000000009003",
} as const;

/** Opens the price lists from the tab of the catalog, with a preset already applied when one is given. */
export async function openPriceLists(page: Page, scenarioId: string, preset?: string): Promise<void> {
  await openCatalog(page, scenarioId, preset);
  await page.getByRole("navigation", { name: "Vistas del catálogo" }).getByRole("link", { name: "Listas de precios" }).click();
  await expect(page).toHaveURL(/\/catalog\/price-lists$/);
  await expect(page.getByRole("heading", { level: 2, name: "Listas de precios" })).toBeVisible();
}

/** Opens the detail of a seeded list by its address, with a preset already applied when one is given. */
export async function openPriceListDetail(page: Page, scenarioId: string, listId: string, name: string, preset?: string): Promise<void> {
  if (preset !== undefined) await applyPreset(page, scenarioId, preset);
  await seedSession(page);
  await page.goto(`${PRICE_LISTS_PATH}/${listId}`);
  await expect(page.getByRole("heading", { level: 2, name })).toBeVisible({ timeout: 20_000 });
}

/** The query of the requests the page makes to the listing of price lists, decoded. */
export function recordPriceListQueries(page: Page): string[] {
  const seen: string[] = [];
  page.on("request", (request: Request) => {
    const url = new URL(request.url());
    if (request.method() === "GET" && url.pathname === "/api/v1/catalog/price-lists") seen.push(decodeURIComponent(url.search));
  });
  return seen;
}

/** The rows of the table of validities of one concept, as text. */
export async function validityRows(page: Page, tableName: string): Promise<string[][]> {
  return page
    // Hidden too: a dialog that is open hides the page behind it from the accessibility tree, and the table is still there.
    .getByRole("table", { name: tableName, includeHidden: true })
    .locator("tbody tr")
    .evaluateAll((rows) => rows.map((row) => [...row.querySelectorAll("td")].map((cell) => cell.textContent.trim())));
}

/** The path of the tax rates, and the seeded rates the journeys name (catalog-tax-rates cut). */
export const TAX_RATES_PATH = "/catalog/tax-rates";
export const SEEDED_RATES = {
  a: "00000000-0000-4000-8000-000000006001",
  b: "00000000-0000-4000-8000-000000006002",
  inactive: "00000000-0000-4000-8000-000000006003",
} as const;

/** Opens the tax rates from the tab of the catalog, with a preset already applied when one is given. */
export async function openTaxRates(page: Page, scenarioId: string, preset?: string): Promise<void> {
  await openCatalog(page, scenarioId, preset);
  await page.getByRole("navigation", { name: "Vistas del catálogo" }).getByRole("link", { name: "Tasas de impuesto" }).click();
  await expect(page).toHaveURL(/\/catalog\/tax-rates$/);
  await expect(page.getByRole("heading", { level: 2, name: "Tasas de impuesto" })).toBeVisible();
}

/** Opens the detail of a seeded rate by its address, with a preset already applied when one is given. */
export async function openTaxRateDetail(page: Page, scenarioId: string, rateId: string, name: string, preset?: string): Promise<void> {
  if (preset !== undefined) await applyPreset(page, scenarioId, preset);
  await seedSession(page);
  await page.goto(`${TAX_RATES_PATH}/${rateId}`);
  await expect(page.getByRole("heading", { level: 2, name })).toBeVisible({ timeout: 20_000 });
}

/** The query of the requests the page makes to the listing of tax rates, decoded. */
export function recordTaxRateQueries(page: Page): string[] {
  const seen: string[] = [];
  page.on("request", (request: Request) => {
    const url = new URL(request.url());
    if (request.method() === "GET" && url.pathname === "/api/v1/catalog/tax-rates") seen.push(decodeURIComponent(url.search));
  });
  return seen;
}

/** The rows of the history of validities of a tax rate, as text. */
export async function periodRows(page: Page, tableName: string): Promise<string[][]> {
  return page
    // Hidden too: a dialog that is open hides the page behind it from the accessibility tree, and the table is still there.
    .getByRole("table", { name: tableName, includeHidden: true })
    .locator("tbody tr")
    .evaluateAll((rows) => rows.map((row) => [...row.querySelectorAll("td")].map((cell) => cell.textContent.trim())));
}
