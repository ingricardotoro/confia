import type { Locator, Page } from "@playwright/test";
import { expect, MOCK_API_URL, seedSession, test as scenarioTest } from "./scenario.ts";

/** The `test` of the journeys that open a dialog: the one of `scenario.ts`. The scroll lock of the modal Dialog carries the nonce of the page, so it raises no CSP report (AW12 applies to it like to any other). */
export const test = scenarioTest;

/**
 * What the journeys of the academic structure share: reaching a view from the navigation of a signed in page, and
 * the few controls the four views repeat. The texts are the ones of the Spanish catalog, as a person reads them.
 */
export const VIEWS = {
  years: { path: "/organization/academic-years", heading: "Años lectivos" },
  modalities: { path: "/organization/modalities", heading: "Modalidades" },
  grades: { path: "/organization/grades", heading: "Grados" },
  sections: { path: "/organization/sections", heading: "Secciones" },
} as const;
export type View = keyof typeof VIEWS;

/** Applies a preset of the simulator to the scenario of the test. It restarts the scenario, so it comes before the session. */
export async function applyPreset(page: Page, scenarioId: string, preset: string): Promise<void> {
  const reply = await page.request.post(`${MOCK_API_URL}/__control/scenarios/${scenarioId}`, { data: { preset } });
  expect(reply.status()).toBe(200);
}

/** Signs in with the seeded session, as the simulator's own route does, and opens the area from the navigation. */
export async function openOrganization(page: Page): Promise<void> {
  await seedSession(page);
  await page.getByRole("navigation", { name: "Navegación principal" }).getByRole("link", { name: "Estructura académica" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Estructura académica" })).toBeVisible();
}

/** Goes to a view through the sub navigation of the area. */
export async function goToView(page: Page, view: View): Promise<void> {
  await page.getByRole("navigation", { name: "Vistas de la estructura académica" }).getByRole("link", { name: VIEWS[view].heading }).click();
  await expect(page).toHaveURL(new RegExp(`${VIEWS[view].path}(\\?.*)?$`));
  await expect(page.getByRole("heading", { level: 2, name: VIEWS[view].heading })).toBeVisible();
}

/** Opens the area on a view with a preset already applied and the session seeded. */
export async function openView(page: Page, scenarioId: string, view: View, preset?: string): Promise<void> {
  if (preset !== undefined) await applyPreset(page, scenarioId, preset);
  await openOrganization(page);
  await goToView(page, view);
}

/** The rows of the body of the table, as the text of each cell. */
export async function bodyRows(page: Page): Promise<string[][]> {
  return page.locator("tbody tr").evaluateAll((rows) => rows.map((row) => [...row.querySelectorAll("td")].map((cell) => cell.textContent.trim())));
}

/** The first cell of each row of the body, which is the name or the code the person reads first. */
export async function firstColumn(page: Page): Promise<string[]> {
  return (await bodyRows(page)).map((cells) => cells[0] ?? "");
}

export const dialog = (page: Page): Locator => page.getByRole("dialog");

/** The control of a labelled field of the open dialog; the label may carry the mark of a required field. */
export const field = (page: Page, label: string): Locator => dialog(page).getByLabel(new RegExp(`^${label}`));

/** The region that tells the result of the last action. */
export const resultNotice = (page: Page): Locator => page.locator('[aria-live="polite"]').filter({ hasText: /\S/ }).first();

/** Opens the create dialog of the open view with its primary action, and returns the dialog. */
export async function openCreate(page: Page, name: string): Promise<Locator> {
  await page.getByRole("button", { name }).click();
  await expect(dialog(page)).toBeVisible();
  return dialog(page);
}

export const save = (page: Page): Locator => dialog(page).getByRole("button", { name: "Guardar" });

/**
 * How long a save may take to close its dialog. The write and the refresh of every list behind it share one preview
 * server and one simulator with the other workers, and the default 5 seconds is not enough when the machine is busy.
 */
export const SAVE_TIMEOUT = { timeout: 15_000 } as const;
