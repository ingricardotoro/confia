import type { Page } from "@playwright/test";
import { expect } from "../../support/scenario.ts";
import { ENROLLMENTS_HEADER, importKind, openImports, recordImportCalls, recordImportResponses, studentsCsv, test } from "../../support/imports.ts";

// The criterion of the reconciliation cut, against the SIMULATED API: with the students and the enrollments imported (one enrollment
// rejected), a person chooses the year, sees what the migration left and, once the official total is typed, the difference in words;
// signs the acta and finds it in the history with its date and counts; signs another and both stay. The account that signed is never
// on the page, and nothing of the imported files reaches it.
const SIGNER_ID = "99999999-9999-4999-8999-999999999999";
const row = (student: number, section = "A"): string => `L${String(student)},2026,Diurna,Primero,${section},2026-02-02`;

const section = (page: Page) => page.getByRole("region", { name: "Acta de conciliación" });
const yearField = (page: Page) => section(page).getByLabel(/^Año lectivo/);
const referenceField = (page: Page) => section(page).getByLabel(/^Referencia del listado oficial/);
const countField = (page: Page) => section(page).getByLabel(/^Total de matrículas del listado oficial/);
const signButton = (page: Page) => section(page).getByRole("button", { name: "Firmar acta" });
const previewTable = (page: Page) => section(page).getByRole("table", { name: /Registros creados y rechazados/ });
const historyTable = (page: Page) => section(page).getByRole("table", { name: /Actas de conciliación firmadas/ });

async function fill(page: Page, reference: string, count: string): Promise<void> {
  await referenceField(page).fill(reference);
  await countField(page).fill(count);
}

test.describe("the acta of the reconciliation", () => {
  test("shows what the migration left, tells the difference with the roster in words, signs, and keeps both actas when signing again", async ({ page, scenarioId }) => {
    const calls = recordImportCalls(page);
    const responses = recordImportResponses(page);
    await openImports(page, scenarioId, "imports-empty");
    await importKind(page, "students", studentsCsv(4), 4);
    // Three enrollments are made and one is rejected: its section does not exist.
    await importKind(page, "enrollments", [ENROLLMENTS_HEADER, row(1), row(2), row(3), row(4, "Nueva")].join("\n"), 3);

    // The active year is chosen and the server's counts of what the imports left are shown, in the order of the imports.
    await expect(section(page).getByRole("heading", { level: 2, name: "Acta de conciliación" })).toBeVisible();
    await expect(yearField(page)).toHaveValue(/.+/);
    await expect(yearField(page).locator("option:checked")).toHaveText("Año lectivo 2026");
    await expect(previewTable(page).getByRole("row").filter({ hasText: "Matrículas" }).getByRole("cell").nth(1)).toHaveText("3");
    const counts = previewTable(page).getByRole("row");
    await expect(counts.nth(1)).toContainText("Estudiantes");
    await expect(counts.nth(1).getByRole("cell").nth(1)).toHaveText("4");
    await expect(counts.nth(4).getByRole("cell").nth(2)).toHaveText("1");
    await expect(section(page).getByText("Matrículas activas del año lectivo").locator("xpath=following-sibling::dd")).toHaveText("3");
    await expect(section(page).getByText("Aún no hay actas firmadas")).toBeVisible();

    // The difference with the official total is said in words as soon as the total is a whole number.
    await expect(section(page).getByText("Escriba el total del listado oficial para ver la diferencia.")).toBeVisible();
    await fill(page, "Oficio 0123-2026", "5");
    await expect(section(page).getByText("El sistema tiene 2 matrículas activas menos que el listado oficial.")).toBeVisible();
    await countField(page).fill("1");
    await expect(section(page).getByText("El sistema tiene 2 matrículas activas más que el listado oficial.")).toBeVisible();
    await countField(page).fill("5");

    // A reference of spaces is refused before anything is sent. Enter sends the form from the field, as a keyboard does: a click would
    // first leave the field, and the error that appears under it moves the button.
    await referenceField(page).fill("   ");
    await referenceField(page).press("Enter");
    await expect(page.getByRole("group", { name: /campo con error/ })).toBeFocused();
    expect(calls.filter((call) => call.startsWith("POST /api/v1/imports/reconciliations"))).toEqual([]);
    await referenceField(page).fill("Oficio 0123-2026");

    await signButton(page).click();

    const notice = section(page).getByRole("status").filter({ hasText: "Acta firmada" });
    await expect(notice).toBeFocused();
    await expect(notice).toContainText("Año lectivo 2026");
    await expect(notice).toContainText("«Oficio 0123-2026»");
    await expect(notice).toContainText("El sistema tiene 2 matrículas activas menos que el listado oficial.");
    await expect(referenceField(page)).toHaveValue("");
    await expect(countField(page)).toHaveValue("");
    await expect(yearField(page).locator("option:checked")).toHaveText("Año lectivo 2026");
    const first = historyTable(page).getByRole("row").nth(1);
    await expect(first.getByRole("cell").nth(1)).toHaveText("Año lectivo 2026");
    await expect(first.getByRole("cell").nth(2)).toHaveText("Oficio 0123-2026");
    await expect(first.getByRole("cell").nth(3)).toHaveText("5");
    await expect(first.getByRole("cell").nth(4)).toHaveText("3");
    await expect(first.getByRole("cell").nth(5)).toHaveText("2 menos");

    // Signing again keeps the first acta: both are in the history, the newest first.
    await fill(page, "Oficio 0124-2026", "3");
    await expect(section(page).getByText("El sistema tiene las mismas matrículas activas que el listado oficial.")).toBeVisible();
    await signButton(page).click();
    await expect(historyTable(page).getByRole("row")).toHaveCount(2 + 1);
    const references = historyTable(page).getByRole("row");
    await expect(references.nth(1).getByRole("cell").nth(2)).toHaveText("Oficio 0124-2026");
    await expect(references.nth(1).getByRole("cell").nth(5)).toHaveText("Coincide");
    await expect(references.nth(2).getByRole("cell").nth(2)).toHaveText("Oficio 0123-2026");

    expect(calls.filter((call) => call === "POST /api/v1/imports/reconciliations")).toHaveLength(2);
    // The account that signed and the files never reach the page or the address.
    await expect(page.locator("body")).not.toContainText(SIGNER_ID);
    await expect(page.locator("body")).not.toContainText("Diurna");
    expect(page.url()).not.toContain(SIGNER_ID);
    expect((await Promise.all(responses)).join("")).not.toMatch(/Nombre\d|Apellido\d|Diurna/);
  });

  test("tells a refusal with 403 in plain words, keeps what was typed and records nothing", async ({ page, scenarioId }) => {
    await openImports(page, scenarioId, "imports-forbidden");
    await expect(yearField(page).locator("option:checked")).toHaveText("Año lectivo 2026");

    await fill(page, "Oficio 0123-2026", "5");
    await signButton(page).click();

    await expect(section(page).getByText(/No tiene permiso para realizar esta acción/)).toBeVisible();
    await expect(referenceField(page)).toHaveValue("Oficio 0123-2026");
    await expect(countField(page)).toHaveValue("5");
    await expect(section(page).getByText("Acta firmada", { exact: true })).toHaveCount(0);
    await expect(section(page).getByText("Aún no hay actas firmadas")).toBeVisible();
  });

  test("shows zeros for a year with nothing in it and signs it", async ({ page, scenarioId }) => {
    await openImports(page, scenarioId, "imports-empty");

    await yearField(page).selectOption({ label: "Año lectivo 2025" });
    await expect(section(page).getByText("Matrículas activas del año lectivo").locator("xpath=following-sibling::dd")).toHaveText("0");
    await fill(page, "Oficio 0001-2025", "0");
    await expect(section(page).getByText("El sistema tiene las mismas matrículas activas que el listado oficial.")).toBeVisible();
    await signButton(page).click();

    await expect(historyTable(page).getByRole("row").nth(1).getByRole("cell").nth(1)).toHaveText("Año lectivo 2025");
    await expect(historyTable(page).getByRole("row").nth(1).getByRole("cell").nth(5)).toHaveText("Coincide");
  });
});
