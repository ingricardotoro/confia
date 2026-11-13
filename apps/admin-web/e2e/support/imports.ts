import type { Locator, Page, Request } from "@playwright/test";
import { applyPreset, dialog } from "./organization.ts";
import { expect, seedSession } from "./scenario.ts";

export { applyPreset, dialog, test } from "./organization.ts";

/** The path of the imports screen. */
export const IMPORTS_PATH = "/imports";

/** Signs in with the seeded session and opens the imports screen from the navigation. */
export async function openImportsPage(page: Page): Promise<void> {
  await seedSession(page);
  await page.getByRole("navigation", { name: "Navegación principal" }).getByRole("link", { name: "Importaciones" }).click();
  await expect(page).toHaveURL(/\/imports$/);
  await expect(page.getByRole("heading", { level: 1, name: "Importaciones" })).toBeVisible();
  await expect(page.getByRole("heading", { level: 2, name: "Importar datos" })).toBeVisible();
}

/** Opens the imports screen with a preset already applied and the session seeded. */
export async function openImports(page: Page, scenarioId: string, preset?: string): Promise<void> {
  if (preset !== undefined) await applyPreset(page, scenarioId, preset);
  await openImportsPage(page);
}

export const fileField = (page: Page): Locator => page.getByLabel("Archivo CSV de estudiantes");
export const simulateButton = (page: Page): Locator => page.getByRole("button", { name: "Simular la importación" });
export const summaryHeading = (page: Page): Locator => page.getByRole("heading", { level: 3, name: "Resultado de la simulación" });
export const resultHeading = (page: Page): Locator => page.getByRole("heading", { level: 3, name: "Importación aplicada" });

/** Fictitious rows only: the names are made up for the journeys and appear nowhere on the screen. */
export const HEADER = "legacyId,firstName,lastName,birthDate,nationalId,sex";
export const goodRow = (id: number): string => `L${String(id)},Nombre${String(id)},Apellido${String(id)},2013-05-${String((id % 28) + 1).padStart(2, "0")},,F`;

/** A CSV of `good` valid rows followed by the given rows, which the journey makes defective on purpose. */
export function csvOf(good: number, ...extra: string[]): string {
  return [HEADER, ...Array.from({ length: good }, (_, index) => goodRow(index + 1)), ...extra].join("\n");
}

/** Chooses a file in memory, as a person chooses one from the disk: nothing is written to the disk. */
export async function choose(page: Page, content: string, name = "estudiantes.csv"): Promise<void> {
  await fileField(page).setInputFiles({ name, mimeType: "text/csv", buffer: Buffer.from(content, "utf8") });
}

/** The method and path of every call the page makes to the imports routes, in order. */
export function recordImportCalls(page: Page): string[] {
  const seen: string[] = [];
  page.on("request", (request: Request) => {
    const path = new URL(request.url()).pathname;
    if (path.startsWith("/api/v1/imports")) seen.push(`${request.method()} ${path}`);
  });
  return seen;
}

/** The text of every answer the API gave to the imports routes, so a journey can prove what never travelled back. */
export function recordImportResponses(page: Page): Promise<string>[] {
  const seen: Promise<string>[] = [];
  page.on("response", (response) => {
    if (new URL(response.url()).pathname.startsWith("/api/v1/imports")) seen.push(response.text().catch(() => ""));
  });
  return seen;
}

/** The kinds of file the screen offers, by the label of the field of each. */
export const FILE_LABELS = {
  students: "Archivo CSV de estudiantes",
  guardians: "Archivo CSV de encargados",
  "guardian-links": "Archivo CSV de vínculos",
  enrollments: "Archivo CSV de matrículas",
} as const;
export type ImportKindName = keyof typeof FILE_LABELS;

/** Chooses what the next file loads, as a person does with the select of the first step. */
export async function chooseKind(page: Page, kind: ImportKindName): Promise<void> {
  await page.getByLabel("Qué va a importar").selectOption(kind);
  await expect(page.getByLabel(FILE_LABELS[kind])).toBeVisible();
}

/** Chooses a file in memory for the kind on the screen. */
export async function chooseFile(page: Page, kind: ImportKindName, content: string, name = "archivo.csv"): Promise<void> {
  await page.getByLabel(FILE_LABELS[kind]).setInputFiles({ name, mimeType: "text/csv", buffer: Buffer.from(content, "utf8") });
}

/** Simulates and applies a file of a kind, and goes back to the first step: how a person loads the three files one after the other. */
export async function importKind(page: Page, kind: ImportKindName, content: string, expectedCreated: number): Promise<void> {
  await chooseKind(page, kind);
  await chooseFile(page, kind, content);
  await simulateButton(page).click();
  await expect(summaryHeading(page)).toBeFocused();
  await page.getByRole("button", { name: "Aplicar la importación" }).click();
  await dialog(page).getByRole("button", { name: new RegExp(`^Crear ${String(expectedCreated)} `) }).click();
  await expect(resultHeading(page)).toBeFocused();
  await page.getByRole("button", { name: "Importar otro archivo" }).click();
}

/** Fictitious rows only, as the rest of the journeys: the names appear nowhere on the screen. */
export const GUARDIANS_HEADER = "legacyId,firstName,lastName";
export const LINKS_HEADER = "studentLegacyId,guardianLegacyId,relationship,financialResponsibilityPercent,isPrimaryPayer,isBillingRecipient";
export const ENROLLMENTS_HEADER = "studentLegacyId,academicYear,modality,grade,section,enrolledOn";
export const studentsCsv = (count: number): string => csvOf(count);
export const guardiansCsv = (count: number): string =>
  [GUARDIANS_HEADER, ...Array.from({ length: count }, (_, index) => `G${String(index + 1)},Encargado${String(index + 1)},Apellido${String(index + 1)}`)].join("\n");
