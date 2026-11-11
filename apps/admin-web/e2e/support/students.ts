import type { Page, Request } from "@playwright/test";
import { applyPreset, dialog, field, save } from "./organization.ts";
import { expect, seedSession } from "./scenario.ts";

export { applyPreset, bodyRows, dialog, field, firstColumn, openCreate, resultNotice, save, SAVE_TIMEOUT, test } from "./organization.ts";

/** The path of the students registry. */
export const STUDENTS_PATH = "/students";

/** Signs in with the seeded session, as the simulator's own route does, and opens the registry from the navigation. */
export async function openStudentsPage(page: Page): Promise<void> {
  await seedSession(page);
  await page.getByRole("navigation", { name: "Navegación principal" }).getByRole("link", { name: "Estudiantes" }).click();
  await expect(page).toHaveURL(/\/students$/);
  await expect(page.getByRole("heading", { level: 1, name: "Estudiantes" })).toBeVisible();
  await expect(page.getByRole("heading", { level: 2, name: "Lista de estudiantes" })).toBeVisible();
}

/** Opens the registry with a preset already applied and the session seeded. */
export async function openStudents(page: Page, scenarioId: string, preset?: string): Promise<void> {
  if (preset !== undefined) await applyPreset(page, scenarioId, preset);
  await openStudentsPage(page);
}

/** The query of the requests the page makes to the listing, decoded, so `sort=lastName,desc` reads as it was sent. */
export function recordStudentQueries(page: Page): string[] {
  const seen: string[] = [];
  page.on("request", (request: Request) => {
    const url = new URL(request.url());
    if (request.method() === "GET" && url.pathname === "/api/v1/students") seen.push(decodeURIComponent(url.search));
  });
  return seen;
}

/** The text of every answer the API gave to the students routes, so a journey can prove what never travelled back. */
export function recordStudentResponses(page: Page): Promise<string>[] {
  const seen: Promise<string>[] = [];
  page.on("response", (response) => {
    if (new URL(response.url()).pathname.startsWith("/api/v1/students")) seen.push(response.text().catch(() => ""));
  });
  return seen;
}

export const summary = (page: Page) => page.getByRole("navigation", { name: "Paginación" });
export const searchBox = (page: Page) => page.getByRole("searchbox", { name: "Buscar" });
export const alertOf = (page: Page) => dialog(page).getByRole("alert");

/** What a journey types in the form of a new student; the document is optional. */
export interface NewStudent {
  readonly code?: string;
  readonly firstName: string;
  readonly lastName: string;
  readonly birthDate: string;
  readonly sex: "Femenino" | "Masculino" | "No especificado";
  readonly document?: string;
}

/** Fills the open create dialog with a student. */
export async function fillStudent(page: Page, student: NewStudent): Promise<void> {
  if (student.code !== undefined) await field(page, "Código").fill(student.code);
  await field(page, "Nombres").fill(student.firstName);
  await field(page, "Apellidos").fill(student.lastName);
  await field(page, "Fecha de nacimiento").fill(student.birthDate);
  await field(page, "Sexo").selectOption({ label: student.sex });
  if (student.document !== undefined) await field(page, "Documento de identidad").fill(student.document);
}

/** Fills the dialog and presses Save; the caller decides what to expect. */
export async function submitStudent(page: Page, student: NewStudent): Promise<void> {
  await fillStudent(page, student);
  await save(page).click();
}
