import type { Locator, Page, Request } from "@playwright/test";
import { applyPreset, dialog } from "./organization.ts";
import { expect, seedSession } from "./scenario.ts";

export { applyPreset, bodyRows, dialog, field, firstColumn, resultNotice, SAVE_TIMEOUT, test } from "./organization.ts";

/** Signs in with the seeded session and opens the enrollments from the navigation. */
export async function openEnrollmentsPage(page: Page): Promise<void> {
  await seedSession(page);
  await page.getByRole("navigation", { name: "Navegación principal" }).getByRole("link", { name: "Matrículas" }).click();
  await expect(page).toHaveURL(/\/enrollments$/);
  await expect(page.getByRole("heading", { level: 1, name: "Matrículas" })).toBeVisible();
  await expect(page.getByRole("heading", { level: 2, name: "Lista de matrículas" })).toBeVisible();
}

/** Opens the enrollments with a preset already applied and the session seeded. */
export async function openEnrollments(page: Page, scenarioId: string, preset?: string): Promise<void> {
  if (preset !== undefined) await applyPreset(page, scenarioId, preset);
  await openEnrollmentsPage(page);
}

/** The query of the requests the page makes to the enrollments listing, decoded, so `sort=status,desc` reads as it was sent. */
export function recordEnrollmentQueries(page: Page): string[] {
  const seen: string[] = [];
  page.on("request", (request: Request) => {
    const url = new URL(request.url());
    if (request.method() === "GET" && url.pathname === "/api/v1/enrollments") seen.push(decodeURIComponent(url.search));
  });
  return seen;
}

export const summary = (page: Page): Locator => page.getByRole("navigation", { name: "Paginación" });
export const alertOf = (page: Page): Locator => dialog(page).getByRole("alert");
export const studentBox = (page: Page): Locator => dialog(page).getByRole("combobox", { name: /Buscar estudiante/ });

/** The day the journeys enroll, move and withdraw on: inside the seeded year and in the past whatever day the suite runs. */
export const ENROLL_DATE = "2026-03-02";
export const MOVE_DATE = "2026-03-10";
export const EXIT_DATE = "2026-04-01";

/** Finds a student by a text and chooses the result with the pointer; the dialog of enrolling is open. */
export async function chooseStudent(page: Page, text: string, option: string): Promise<void> {
  await studentBox(page).fill(text);
  await dialog(page).getByRole("option", { name: option }).click();
  await expect(dialog(page).getByText(option)).toBeVisible();
}

/** Opens the enroll dialog, chooses the student and the section (by id) and sets the date; the caller presses the button. */
export async function fillEnrollment(page: Page, input: { text: string; option: string; sectionId: string; date: string }): Promise<void> {
  await page.getByRole("button", { name: "Matricular", exact: true }).click();
  await expect(dialog(page)).toBeVisible();
  await chooseStudent(page, input.text, input.option);
  await dialog(page).getByLabel(/^Sección/).selectOption(input.sectionId);
  await dialog(page).getByLabel(/^Fecha de matrícula/).fill(input.date);
}

export const submitEnroll = (page: Page): Locator => dialog(page).getByRole("button", { name: "Matricular" });
