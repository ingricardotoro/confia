import type { Page } from "@playwright/test";
import { expect } from "../../support/scenario.ts";
import {
  ENROLLMENTS_HEADER,
  FILE_LABELS,
  chooseFile,
  chooseKind,
  dialog,
  importKind,
  openImports,
  recordImportCalls,
  recordImportResponses,
  resultHeading,
  simulateButton,
  studentsCsv,
  summaryHeading,
  test,
} from "../../support/imports.ts";

// The criterion of the enrollments cut, against the SIMULATED API: with 100 students imported, a person imports 100 enrollments with 7
// defective rows (a date that does not exist, a section without a seat left, a section that does not exist, one that is ambiguous, one
// that is inactive, a closed year and a date out of the year), sees exactly those 7 by line and reason, corrects, applies, and applying
// again changes nothing. The files and their rows never appear on the page.
const STUDENTS = 100;
const ENROLLMENTS_HEADER_CELLS = ENROLLMENTS_HEADER.split(",").length;

const row = (student: number, section = "A", year = "2026", grade = "Primero", date = "2026-02-02"): string => `L${String(student)},${year},Diurna,${grade},${section},${date}`;

/** One row per student. Section B has two seats in the simulated structure: the third student sent to it does not fit. */
function enrollmentsCsv(defective: boolean): string {
  const rows: string[] = [ENROLLMENTS_HEADER];
  for (let student = 1; student <= STUDENTS; student += 1) {
    if (student === 91 || student === 92) rows.push(row(student, "B"));
    else if (defective && student === 90) rows.push(row(student, "A", "2026", "Primero", "2026-02-30"));
    else if (defective && student === 93) rows.push(row(student, "B"));
    else if (defective && student === 94) rows.push(row(student, "Nueva"));
    else if (defective && student === 95) rows.push(row(student, "Z", "2026", "Segundo"));
    else if (defective && student === 96) rows.push(row(student, "C"));
    else if (defective && student === 97) rows.push(row(student, "A", "2025", "Primero", "2025-02-02"));
    else if (defective && student === 98) rows.push(row(student, "A", "2026", "Primero", "2027-01-01"));
    else rows.push(row(student));
  }
  return rows.join("\n");
}

const rejectionsTable = (page: Page) => page.getByRole("table", { name: /Filas del archivo con su línea/ });

test.describe("the import of enrollments", () => {
  test("imports 100 enrollments with 7 defective rows, rejects exactly those rows, corrects, applies and reapplies with nothing created", async ({ page, scenarioId }) => {
    const calls = recordImportCalls(page);
    const responses = recordImportResponses(page);
    await openImports(page, scenarioId, "imports-empty");
    await importKind(page, "students", studentsCsv(STUDENTS), STUDENTS);

    await chooseKind(page, "enrollments");
    await chooseFile(page, "enrollments", enrollmentsCsv(true));
    await simulateButton(page).click();

    await expect(summaryHeading(page)).toBeFocused();
    await expect(page.getByRole("status").filter({ hasText: "Simulación lista" })).toContainText("93 filas se pueden crear, 7 rechazadas y 0 ya importadas.");
    const rows = rejectionsTable(page).getByRole("row");
    await expect(rows).toHaveCount(7 + 1);
    await expect(rows.nth(1)).toContainText("91enrolledOnLa fecha no existe o es anterior al 1 de enero de 1990.");
    await expect(rows.nth(2)).toContainText("94sectionLa sección no tiene cupo para esta matrícula.");
    await expect(rows.nth(3)).toContainText("95sectionNo existe una sección con ese año lectivo, modalidad, grado y nombre");
    await expect(rows.nth(4)).toContainText("96sectionMás de una sección coincide con esos nombres");
    await expect(rows.nth(5)).toContainText("97sectionLa sección no está activa.");
    await expect(rows.nth(6)).toContainText("98academicYearEl año lectivo está cerrado: no admite matrículas.");
    await expect(rows.nth(7)).toContainText("99enrolledOnLa fecha de matrícula está fuera del año lectivo o es posterior a hoy.");

    // The person corrects the source and simulates again: no rejection is left.
    await chooseFile(page, "enrollments", enrollmentsCsv(false), "corregido.csv");
    await expect(summaryHeading(page)).toBeHidden();
    await simulateButton(page).click();
    await expect(summaryHeading(page)).toBeFocused();
    await expect(page.getByText("Ninguna fila tiene defectos.")).toBeVisible();

    await page.getByRole("button", { name: "Aplicar la importación" }).click();
    await expect(dialog(page).getByText("Se crearán 100 matrículas.")).toBeVisible();
    await expect(dialog(page).getByText(/cupo se revisa de nuevo al aplicar/)).toBeVisible();
    await dialog(page).getByRole("button", { name: "Crear 100 matrículas" }).click();

    await expect(resultHeading(page)).toBeFocused();
    await expect(page.getByText(/Se crearon 100 matrículas\./)).toBeVisible();

    // The same file once more: every student is already in that section, so every row is skipped and nothing is created.
    await page.getByRole("button", { name: "Importar otro archivo" }).click();
    await chooseFile(page, "enrollments", enrollmentsCsv(false), "corregido.csv");
    await simulateButton(page).click();
    await expect(page.getByRole("status").filter({ hasText: "Simulación lista" })).toContainText("0 filas se pueden crear, 0 rechazadas y 100 ya importadas.");
    await expect(page.getByText("No hay nada que crear")).toBeVisible();
    await expect(page.getByRole("button", { name: "Aplicar la importación" })).toHaveCount(0);

    expect(calls.filter((call) => call.startsWith("POST") && call.endsWith("/apply"))).toEqual([
      "POST /api/v1/imports/students/apply",
      "POST /api/v1/imports/enrollments/apply",
    ]);
    // Nothing of the files reached the page, the address or an answer of the API.
    await expect(page.locator("body")).not.toContainText("corregido.csv");
    await expect(page.locator("body")).not.toContainText("Diurna");
    expect(page.url()).not.toContain("L1");
    expect((await Promise.all(responses)).join("")).not.toMatch(/Nombre\d|Apellido\d|Diurna/);
  });

  test("never moves a student: the same section is skipped, another section is rejected, and a seat that is gone is a rejection", async ({ page, scenarioId }) => {
    await openImports(page, scenarioId, "imports-empty");
    await importKind(page, "students", studentsCsv(4), 4);
    await importKind(page, "enrollments", [ENROLLMENTS_HEADER, row(1, "A"), row(2, "B")].join("\n"), 2);

    await chooseKind(page, "enrollments");
    await chooseFile(page, "enrollments", [ENROLLMENTS_HEADER, row(1, "A"), row(2, "A"), row(3, "B"), row(4, "B")].join("\n"));
    await simulateButton(page).click();

    await expect(summaryHeading(page)).toBeFocused();
    await expect(page.getByRole("status").filter({ hasText: "Simulación lista" })).toContainText("1 fila se puede crear, 2 rechazadas y 1 ya importada.");
    const rows = rejectionsTable(page).getByRole("row");
    await expect(rows).toHaveCount(3 + 1);
    await expect(rows.nth(1)).toContainText("2studentLegacyIdYa se importó antes: se omite.");
    await expect(rows.nth(2)).toContainText("3studentLegacyIdEl estudiante ya tiene una matrícula activa en ese año en otra sección");
    await expect(rows.nth(3)).toContainText("5sectionLa sección no tiene cupo para esta matrícula.");
  });

  test("finds the section whatever the capitals and the spaces, and says a student that is not imported yet", async ({ page, scenarioId }) => {
    await openImports(page, scenarioId, "imports-empty");
    await expect(page.getByLabel("Qué va a importar")).toHaveAccessibleDescription(/los vínculos y por último las matrículas/);
    await importKind(page, "students", studentsCsv(1), 1);

    await chooseKind(page, "enrollments");
    await chooseFile(page, "enrollments", [ENROLLMENTS_HEADER, "L1, 2026 , diurna ,PRIMERO, a ,2026-02-02", row(7)].join("\n"));
    await simulateButton(page).click();

    await expect(page.getByRole("status").filter({ hasText: "Simulación lista" })).toContainText("1 fila se puede crear, 1 rechazada y 0 ya importadas.");
    await expect(rejectionsTable(page).getByRole("row").nth(1)).toContainText("3studentLegacyIdEl estudiante no está importado: importe primero el archivo de estudiantes.");
  });

  test("offers the template of the enrollments with its example row and lets go of the file when the kind changes", async ({ page, scenarioId }) => {
    await openImports(page, scenarioId);

    await chooseKind(page, "enrollments");
    const [download] = await Promise.all([page.waitForEvent("download"), page.getByRole("button", { name: "Descargar la plantilla vacía" }).click()]);
    expect(download.suggestedFilename()).toBe("plantilla-matriculas.csv");
    const chunks: Buffer[] = [];
    for await (const chunk of await download.createReadStream()) chunks.push(chunk as Buffer);
    const text = Buffer.concat(chunks).toString("utf8");
    expect(text).toBe(`${ENROLLMENTS_HEADER}\r\nL1001,2026,Diurna,Primero,A,2026-02-02\r\n`);
    expect(text.split("\r\n")[0]?.split(",")).toHaveLength(ENROLLMENTS_HEADER_CELLS);

    await chooseFile(page, "enrollments", [ENROLLMENTS_HEADER, row(1)].join("\n"));
    await simulateButton(page).click();
    await expect(summaryHeading(page)).toBeFocused();
    await chooseKind(page, "students");
    await expect(summaryHeading(page)).toBeHidden();
    await expect(page.getByLabel(FILE_LABELS.students)).toHaveValue("");
  });

  test("shows the enrollments in the history and filters it by that kind from the address", async ({ page, scenarioId }) => {
    await openImports(page, scenarioId, "imports-empty");
    await importKind(page, "students", studentsCsv(2), 2);
    await importKind(page, "enrollments", [ENROLLMENTS_HEADER, row(1), row(2)].join("\n"), 2);

    const history = page.getByRole("table", { name: /Importaciones de la institución/ });
    await expect(history.getByRole("row")).toHaveCount(4 + 1);

    await page.getByLabel("Tipo de importación").selectOption("enrollments");
    await expect(page).toHaveURL(/[?&]kind=enrollments/);
    await expect(history.getByRole("row")).toHaveCount(2 + 1);
    await expect(history.getByRole("cell", { name: "Matrículas" })).toHaveCount(2);
    await expect(history.getByRole("cell", { name: "Estudiantes" })).toHaveCount(0);
  });
});
