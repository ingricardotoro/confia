import type { Locator, Page, Request } from "@playwright/test";
import { applyPreset, dialog } from "./organization.ts";
import { expect, seedSession } from "./scenario.ts";

export { applyPreset, bodyRows, dialog, field, firstColumn, openCreate, resultNotice, save, SAVE_TIMEOUT, test } from "./organization.ts";

/** The path of the guardians registry. */
export const GUARDIANS_PATH = "/guardians";

/** Signs in with the seeded session, as the simulator's own route does, and opens the registry from the navigation. */
export async function openGuardiansPage(page: Page): Promise<void> {
  await seedSession(page);
  await page.getByRole("navigation", { name: "Navegación principal" }).getByRole("link", { name: "Encargados" }).click();
  await expect(page).toHaveURL(/\/guardians$/);
  await expect(page.getByRole("heading", { level: 1, name: "Encargados" })).toBeVisible();
  await expect(page.getByRole("heading", { level: 2, name: "Lista de encargados" })).toBeVisible();
}

/** Opens the registry with a preset already applied and the session seeded. */
export async function openGuardians(page: Page, scenarioId: string, preset?: string): Promise<void> {
  if (preset !== undefined) await applyPreset(page, scenarioId, preset);
  await openGuardiansPage(page);
}

/** Signs in and opens the students screen from the navigation, where the guardians of a student are managed. */
export async function openStudentsForLinks(page: Page, scenarioId: string, preset?: string): Promise<void> {
  if (preset !== undefined) await applyPreset(page, scenarioId, preset);
  await seedSession(page);
  await page.getByRole("navigation", { name: "Navegación principal" }).getByRole("link", { name: "Estudiantes" }).click();
  await expect(page.getByRole("heading", { level: 2, name: "Lista de estudiantes" })).toBeVisible();
}

/** The query of the requests the page makes to the listing, decoded, so `sort=code,desc` reads as it was sent. */
export function recordGuardianQueries(page: Page): string[] {
  const seen: string[] = [];
  page.on("request", (request: Request) => {
    const url = new URL(request.url());
    if (request.method() === "GET" && url.pathname === "/api/v1/guardians") seen.push(decodeURIComponent(url.search));
  });
  return seen;
}

/** The text of every answer the API gave to the guardians routes, so a journey can prove what never travelled back. */
export function recordGuardianResponses(page: Page): Promise<string>[] {
  const seen: Promise<string>[] = [];
  page.on("response", (response) => {
    const path = new URL(response.url()).pathname;
    if (path.startsWith("/api/v1/guardians") || /^\/api\/v1\/students\/[^/]+\/guardians$/.test(path)) seen.push(response.text().catch(() => ""));
  });
  return seen;
}

export const summary = (page: Page): Locator => page.getByRole("navigation", { name: "Paginación" });
export const searchBox = (page: Page): Locator => page.getByRole("searchbox", { name: "Buscar" });
export const alertOf = (page: Page): Locator => dialog(page).getByRole("alert");

/** The row of the guardians dialog of one guardian, by its legend. */
export const linkRow = (page: Page, legend: string): Locator => dialog(page).getByRole("group", { name: legend });
export const addBox = (page: Page): Locator => dialog(page).getByRole("combobox", { name: /Agregar un encargado/ });
export const saveLinks = (page: Page): Locator => dialog(page).getByRole("button", { name: "Guardar encargados" });

/** Opens the dialog of the guardians of a student of the students screen, filtered by the last name so the row is on the page. */
export async function openLinks(page: Page, lastName: string, studentName: string): Promise<void> {
  await page.getByRole("searchbox", { name: "Buscar" }).fill(lastName);
  await page.getByRole("button", { name: `Encargados de ${studentName}` }).click();
  await expect(dialog(page)).toBeVisible();
  await expect(dialog(page).getByText(/Suma guardada actualmente/)).toBeVisible();
}

/** Sets the percentage of a row of the dialog. */
export async function setPercent(page: Page, legend: string, value: string): Promise<void> {
  await linkRow(page, legend).getByLabel(/^Porcentaje/).fill(value);
}
