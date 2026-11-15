import { expect } from "../../support/scenario.ts";
import {
  FILE_LABELS,
  GUARDIANS_HEADER,
  LINKS_HEADER,
  chooseFile,
  chooseKind,
  dialog,
  guardiansCsv,
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

// The criterion of the guardians cut, against the SIMULATED API: with 100 students imported, a person imports 80 guardians and 120
// links with 6 defective groups (a sum of 90), sees exactly those groups rejected, corrects, applies, and applying again changes
// nothing. The files and their rows never appear on the page.
const STUDENTS_WITH_LINKS = 60;
const DEFECTIVE_FIRST = 55;

/** Two links per student, to two different guardians out of the 80; the defective groups add up to 90 instead of 100. */
function linksCsv(defective: boolean): string {
  const rows: string[] = [LINKS_HEADER];
  for (let student = 1; student <= STUDENTS_WITH_LINKS; student += 1) {
    const first = (2 * student) % 80;
    const second = (2 * student + 1) % 80;
    const broken = defective && student >= DEFECTIVE_FIRST;
    rows.push(`L${String(student)},G${String(first + 1)},FATHER,60,true,true`);
    rows.push(`L${String(student)},G${String(second + 1)},MOTHER,${broken ? "30" : "40"},,`);
  }
  return rows.join("\n");
}

test.describe("the import of guardians and links", () => {
  test("imports 80 guardians and 120 links with 6 defective groups, rejects exactly those groups, corrects, applies and reapplies with nothing created", async ({
    page,
    scenarioId,
  }) => {
    const calls = recordImportCalls(page);
    const responses = recordImportResponses(page);
    await openImports(page, scenarioId, "imports-empty");

    await importKind(page, "students", studentsCsv(100), 100);
    await importKind(page, "guardians", guardiansCsv(80), 80);

    await chooseKind(page, "guardian-links");
    await chooseFile(page, "guardian-links", linksCsv(true));
    await simulateButton(page).click();

    await expect(summaryHeading(page)).toBeFocused();
    await expect(page.getByRole("status").filter({ hasText: "Simulación lista" })).toContainText("108 filas se pueden crear, 12 rechazadas y 0 ya importadas.");
    const table = page.getByRole("table", { name: /Filas del archivo con su línea/ });
    // Six groups of two lines, from line 110 to line 121, each line told that the sum is not 100.00; ten per page.
    await expect(table.getByRole("row")).toHaveCount(10 + 1);
    await expect(table.getByRole("row").nth(1)).toContainText("110financialResponsibilityPercentLos porcentajes de este estudiante no suman exactamente 100.00");
    await page.getByRole("navigation", { name: "Páginas de las filas rechazadas en la simulación" }).getByRole("button", { name: /Siguiente/ }).click();
    await expect(table.getByRole("row")).toHaveCount(2 + 1);
    await expect(table.getByRole("row").nth(2)).toContainText("121financialResponsibilityPercent");

    // The person corrects the source and simulates again: no rejection is left.
    await chooseFile(page, "guardian-links", linksCsv(false), "corregido.csv");
    await expect(summaryHeading(page)).toBeHidden();
    await simulateButton(page).click();
    await expect(summaryHeading(page)).toBeFocused();
    await expect(page.getByText("Ninguna fila tiene defectos.")).toBeVisible();

    await page.getByRole("button", { name: "Aplicar la importación" }).click();
    await expect(dialog(page).getByText("Se crearán 120 vínculos.")).toBeVisible();
    await expect(dialog(page).getByText(/se omite, nunca se reemplazan/)).toBeVisible();
    await dialog(page).getByRole("button", { name: "Crear 120 vínculos" }).click();

    await expect(resultHeading(page)).toBeFocused();
    await expect(page.getByText(/Se crearon 120 vínculos\./)).toBeVisible();

    // The same file once more: every student already has links, so every line is skipped and nothing is created.
    await page.getByRole("button", { name: "Importar otro archivo" }).click();
    await chooseFile(page, "guardian-links", linksCsv(false), "corregido.csv");
    await simulateButton(page).click();
    await expect(page.getByRole("status").filter({ hasText: "Simulación lista" })).toContainText("0 filas se pueden crear, 0 rechazadas y 120 ya importadas.");
    await expect(page.getByText("No hay nada que crear")).toBeVisible();
    await expect(page.getByRole("table", { name: /Filas del archivo con su línea/ }).getByRole("row").nth(1)).toContainText(
      "El estudiante ya tiene encargados vinculados: se omite",
    );
    await expect(page.getByRole("button", { name: "Aplicar la importación" })).toHaveCount(0);

    expect(calls.filter((call) => call.startsWith("POST") && call.endsWith("/apply"))).toEqual([
      "POST /api/v1/imports/students/apply",
      "POST /api/v1/imports/guardians/apply",
      "POST /api/v1/imports/guardian-links/apply",
    ]);
    // Nothing of the files reached the page, the address or an answer of the API.
    await expect(page.locator("body")).not.toContainText("Encargado1");
    await expect(page.locator("body")).not.toContainText("corregido.csv");
    expect(page.url()).not.toContain("Encargado");
    expect((await Promise.all(responses)).join("")).not.toMatch(/Nombre\d|Encargado\d|Apellido\d/);
  });

  test("rejects the whole group when one of its lines has a defect, and lets the valid groups go on", async ({ page, scenarioId }) => {
    await openImports(page, scenarioId, "imports-empty");
    await importKind(page, "students", studentsCsv(3), 3);
    await importKind(page, "guardians", guardiansCsv(2), 2);

    await chooseKind(page, "guardian-links");
    await chooseFile(
      page,
      "guardian-links",
      [LINKS_HEADER, "L1,G1,FATHER,50,,", "L1,G2,MOTHER,50.555,,", "L2,G1,FATHER,100,,", "L3,G1,FATHER,50,true,", "L3,G2,MOTHER,50,true,"].join("\n"),
    );
    await simulateButton(page).click();

    await expect(summaryHeading(page)).toBeFocused();
    await expect(page.getByRole("status").filter({ hasText: "Simulación lista" })).toContainText("1 fila se puede crear, 4 rechazadas y 0 ya importadas.");
    const rows = page.getByRole("table", { name: /Filas del archivo con su línea/ }).getByRole("row");
    await expect(rows).toHaveCount(4 + 1);
    await expect(rows.nth(1)).toContainText("2Fila completaOtra fila de este estudiante tiene un defecto: se rechazan todas sus filas juntas.");
    await expect(rows.nth(2)).toContainText("3financialResponsibilityPercentEl porcentaje no es válido");
    await expect(rows.nth(3)).toContainText("5isPrimaryPayerEste estudiante tiene más de un pagador principal");
    await expect(rows.nth(4)).toContainText("6isPrimaryPayerEste estudiante tiene más de un pagador principal");
    await expect(page.getByRole("button", { name: "Aplicar la importación" })).toBeVisible();
  });

  test("says a student or guardian that is not imported yet, and points to the order of the files", async ({ page, scenarioId }) => {
    await openImports(page, scenarioId, "imports-empty");
    await expect(page.getByLabel("Qué va a importar")).toHaveAccessibleDescription(/los vínculos y por último las matrículas/);

    await chooseKind(page, "guardian-links");
    await chooseFile(page, "guardian-links", [LINKS_HEADER, "L1,G1,FATHER,100,,"].join("\n"));
    await simulateButton(page).click();

    const row = page.getByRole("table", { name: /Filas del archivo con su línea/ }).getByRole("row").nth(1);
    await expect(row).toContainText("studentLegacyIdEl estudiante no está importado: importe primero el archivo de estudiantes.");
    await expect(page.getByRole("table", { name: /Filas del archivo con su línea/ }).getByRole("row").nth(2)).toContainText(
      "guardianLegacyIdEl encargado no está importado: importe primero el archivo de encargados.",
    );
    await expect(page.getByText("No hay nada que crear")).toBeVisible();
  });

  test("offers the empty template of each kind and lets go of the file when the kind changes", async ({ page, scenarioId }) => {
    await openImports(page, scenarioId);
    const templates = [
      ["guardians", "plantilla-encargados.csv", "legacyId,firstName,lastName,nationalId,rtn,email,phone,address,preferredChannel"],
      ["guardian-links", "plantilla-vinculos.csv", LINKS_HEADER],
    ] as const;

    for (const [kind, fileName, header] of templates) {
      await chooseKind(page, kind);
      const [download] = await Promise.all([page.waitForEvent("download"), page.getByRole("button", { name: "Descargar la plantilla vacía" }).click()]);
      expect(download.suggestedFilename()).toBe(fileName);
      const chunks: Buffer[] = [];
      for await (const chunk of await download.createReadStream()) chunks.push(chunk as Buffer);
      expect(Buffer.concat(chunks).toString("utf8")).toBe(`${header}\r\n`);
    }

    await chooseFile(page, "guardian-links", [LINKS_HEADER, "L1,G1,FATHER,100,,"].join("\n"));
    await simulateButton(page).click();
    await expect(summaryHeading(page)).toBeFocused();
    await chooseKind(page, "students");
    await expect(summaryHeading(page)).toBeHidden();
    await expect(page.getByLabel(FILE_LABELS.students)).toHaveValue("");
  });

  test("shows the kind of each batch in the history and filters it by kind from the address", async ({ page, scenarioId }) => {
    await openImports(page, scenarioId, "imports-empty");
    await importKind(page, "students", studentsCsv(2), 2);
    await importKind(page, "guardians", guardiansCsv(2), 2);

    const history = page.getByRole("table", { name: /Importaciones de la institución/ });
    await expect(history.getByRole("columnheader", { name: "Tipo" })).toBeVisible();
    await expect(history.getByRole("row")).toHaveCount(4 + 1);

    await page.getByLabel("Tipo de importación").selectOption("guardians");
    await expect(page).toHaveURL(/[?&]kind=guardians/);
    await expect(history.getByRole("row")).toHaveCount(2 + 1);
    await expect(history.getByRole("cell", { name: "Estudiantes" })).toHaveCount(0);

    await page.getByLabel("Tipo de importación").selectOption("guardian-links");
    await expect(page.getByText("Ningún registro coincide con los filtros")).toBeVisible();
    await page.getByRole("button", { name: "Quitar filtros" }).click();
    await expect(page).not.toHaveURL(/kind=/);
    await expect(history.getByRole("row")).toHaveCount(4 + 1);
  });

  test("names each paginated region apart: the rejections of the simulation, the result and the history", async ({ page, scenarioId }) => {
    await openImports(page, scenarioId, "imports-empty");
    await importKind(page, "students", studentsCsv(3), 3);
    await chooseKind(page, "guardians");
    await chooseFile(page, "guardians", [GUARDIANS_HEADER, ...Array.from({ length: 12 }, (_, index) => `X${String(index)},,Apellido`)].join("\n"));
    await simulateButton(page).click();
    await expect(summaryHeading(page)).toBeFocused();

    await expect(page.getByRole("navigation", { name: "Páginas de las filas rechazadas en la simulación" })).toBeVisible();
    await expect(page.getByRole("navigation", { name: "Páginas del historial de importaciones" })).toBeVisible();
  });
});
