import { SECTION_IDS } from "../../mock-api/organization-seed.ts";
import {
  ENROLL_DATE,
  EXIT_DATE,
  MOVE_DATE,
  bodyRows,
  dialog,
  field,
  fillEnrollment,
  openEnrollments,
  resultNotice,
  SAVE_TIMEOUT,
  submitEnroll,
  summary,
  test,
} from "../../support/enrollments.ts";
import { expect } from "../../support/scenario.ts";

// The acceptance criterion of the cut: an administrator enrolls a student in a section, moves them to another section of the
// same year and then registers their exit; the detail shows the history and the seat of the section is released. Every
// step is asserted on what a person reads. Against the SIMULATED API, which repeats only the rules these journeys need.
const ZELAYA = { text: "Zelaya", option: "Andrea Zelaya · E-000059" } as const;
const rowOf = (page: import("@playwright/test").Page, name: string) => page.getByRole("row", { name: new RegExp(name) });

test.describe("enroll, move and register the exit", () => {
  test("enrolls a student, moves them to another section of the year, registers the exit, and the detail shows the history", async ({ page, scenarioId }) => {
    await openEnrollments(page, scenarioId);
    await expect.poll(async () => (await bodyRows(page)).length).toBe(25);
    await expect(summary(page)).toContainText("Mostrando 1 a 25 de 37");

    // Enroll: the student is found by name, the sections are those of the year in progress, grouped by modality and grade.
    await fillEnrollment(page, { ...ZELAYA, sectionId: SECTION_IDS.firstB, date: ENROLL_DATE });
    await expect(dialog(page).locator('optgroup[label="Primaria · Primero"]')).toHaveCount(1);
    await submitEnroll(page).click();

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Se matriculó a «Andrea Zelaya».");
    await expect(summary(page)).toContainText("Mostrando 1 a 25 de 38");
    // Last name descending puts the new student first, so the list shows the row the server sorted there.
    await page.getByRole("columnheader", { name: "Estudiante" }).getByRole("button").click();
    await expect(page).toHaveURL(/sort=studentLastName(%3A|:)desc/);
    await expect(rowOf(page, "Zelaya, Andrea")).toContainText("Primero · B");
    await expect(rowOf(page, "Zelaya, Andrea")).toContainText("Activa");

    // Move to a section of the same year.
    await page.getByRole("button", { name: "Trasladar de sección a Andrea Zelaya" }).click();
    await expect(dialog(page)).toBeVisible();
    // The section it is in is not offered, and every section is of the same year.
    await expect(dialog(page).getByLabel(/^Sección nueva/).locator(`option[value="${SECTION_IDS.firstB}"]`)).toHaveCount(0);
    await field(page, "Sección nueva").selectOption(SECTION_IDS.firstA);
    await field(page, "Fecha del traslado").fill(MOVE_DATE);
    await field(page, "Motivo").fill("Cambio de turno");
    await dialog(page).getByRole("button", { name: "Trasladar" }).click();

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Se trasladó a «Andrea Zelaya» a la sección A.");
    await expect(rowOf(page, "Zelaya, Andrea")).toContainText("Primero · A");

    // Register the exit.
    await page.getByRole("button", { name: "Registrar salida de Andrea Zelaya" }).click();
    await field(page, "Tipo de salida").selectOption("WITHDRAWN");
    await field(page, "Fecha de salida").fill(EXIT_DATE);
    await field(page, "Motivo").fill("Cambio de ciudad");
    await dialog(page).getByRole("button", { name: "Registrar salida" }).click();

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Se registró la salida de «Andrea Zelaya».");
    await expect(rowOf(page, "Zelaya, Andrea")).toContainText("Retirada");
    // A final enrollment offers no more actions, and the focus moved to what is left of its row.
    await expect(page.getByRole("button", { name: "Registrar salida de Andrea Zelaya" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Trasladar de sección a Andrea Zelaya" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Ver detalle de la matrícula de Andrea Zelaya" })).toBeFocused();

    // The detail: the facts, the reason of the exit and the history of the move, read only.
    await page.getByRole("button", { name: "Ver detalle de la matrícula de Andrea Zelaya" }).click();
    const detail = dialog(page);
    await expect(detail.getByRole("heading", { name: "Detalle de la matrícula" })).toBeVisible();
    await expect(detail.getByText("Cambio de ciudad")).toBeVisible();
    const history = detail.getByRole("table", { name: /Traslados de sección/ });
    await expect(history.getByRole("row")).toHaveCount(2);
    await expect(history.getByRole("row").nth(1)).toContainText("Cambio de turno");
    await expect(history.getByRole("row").nth(1)).toContainText("B");
    await expect(detail.getByRole("textbox")).toHaveCount(0);
    await page.keyboard.press("Escape");
    await expect(dialog(page)).toBeHidden();
    await expect(page.getByRole("button", { name: "Ver detalle de la matrícula de Andrea Zelaya" })).toBeFocused();
  });

  test("the seat of a full section is released by an exit, and the same student can then be enrolled again", async ({ page, scenarioId }) => {
    await openEnrollments(page, scenarioId);

    // "Segundo A" holds 25 of 25.
    await fillEnrollment(page, { ...ZELAYA, sectionId: SECTION_IDS.secondA, date: ENROLL_DATE });
    await submitEnroll(page).click();
    await expect(dialog(page).getByRole("alert")).toContainText("La sección ya alcanzó su cupo.");
    await page.getByRole("button", { name: "Cancelar" }).click();
    await expect(dialog(page)).toBeHidden();

    await page.getByRole("button", { name: "Registrar salida de Ana Aguilar" }).click();
    await field(page, "Fecha de salida").fill(EXIT_DATE);
    await field(page, "Motivo").fill("Cambio de ciudad");
    await dialog(page).getByRole("button", { name: "Registrar salida" }).click();
    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);

    await fillEnrollment(page, { ...ZELAYA, sectionId: SECTION_IDS.secondA, date: ENROLL_DATE });
    await submitEnroll(page).click();

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Se matriculó a «Andrea Zelaya».");
  });

  test("a student who left the year can be enrolled again in it, and an active one cannot be enrolled twice", async ({ page, scenarioId }) => {
    await openEnrollments(page, scenarioId);

    // Student 31 left in May; student 1 is still enrolled in "Segundo A".
    await fillEnrollment(page, { text: "E-000031", option: "Daniela Martínez · E-000031", sectionId: SECTION_IDS.firstB, date: ENROLL_DATE });
    await submitEnroll(page).click();
    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Se matriculó a «Daniela Martínez».");

    await fillEnrollment(page, { text: "Aguilar", option: "Ana Aguilar · E-000001", sectionId: SECTION_IDS.firstB, date: ENROLL_DATE });
    await submitEnroll(page).click();
    await expect(dialog(page).getByRole("alert")).toContainText("El estudiante ya tiene una matrícula activa en ese año lectivo.");
  });

  test("a final enrollment offers only its detail, in every state but active", async ({ page, scenarioId }) => {
    await openEnrollments(page, scenarioId);

    for (const [status, count] of [["WITHDRAWN", 6], ["GRADUATED", 1]] as const) {
      await page.getByLabel("Estado").selectOption(status);
      await expect.poll(async () => (await bodyRows(page)).length).toBe(count);
      for (const row of await page.locator("tbody tr").all()) {
        await expect(row.getByRole("button")).toHaveCount(1);
        await expect(row.getByRole("button", { name: /^Ver detalle/ })).toBeVisible();
      }
    }
  });
});
