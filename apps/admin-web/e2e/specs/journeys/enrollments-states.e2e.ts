import { SECTION_IDS } from "../../mock-api/organization-seed.ts";
import {
  ENROLL_DATE,
  alertOf,
  bodyRows,
  dialog,
  field,
  fillEnrollment,
  openEnrollments,
  resultNotice,
  submitEnroll,
  test,
} from "../../support/enrollments.ts";
import { expect } from "../../support/scenario.ts";

// What the enrollments say when the answer is not a plain yes: a business rule of the server (a full section, a date out of the
// year, an exit without a reason), a refusal for lack of permission, a list with nothing in it and a list that fails to load.
// Texts are the Spanish ones a person reads. Against the SIMULATED API, which repeats only the rules these journeys need.
const ZELAYA = { text: "Zelaya", option: "Andrea Zelaya · E-000059" } as const;

test.describe("a rule of the server refuses a write", () => {
  test("a date a day before the start of the year is refused under its field, the dialog keeps what was chosen, and the first day is then accepted", async ({
    page,
    scenarioId,
  }) => {
    await openEnrollments(page, scenarioId);
    await fillEnrollment(page, { ...ZELAYA, sectionId: SECTION_IDS.firstB, date: "2026-01-14" });

    await submitEnroll(page).click();

    await expect(dialog(page).getByRole("group", { name: "Hay 1 campo con error." })).toBeFocused();
    await expect(field(page, "Fecha de matrícula")).toHaveAccessibleDescription(/La fecha está fuera del rango permitido/);
    await expect(dialog(page).getByText("Andrea Zelaya · E-000059")).toBeVisible();
    await expect(field(page, "Sección")).toHaveValue(SECTION_IDS.firstB);

    await field(page, "Fecha de matrícula").fill("2026-01-15");
    await submitEnroll(page).click();

    await expect(dialog(page)).toBeHidden({ timeout: 15_000 });
    await expect(resultNotice(page)).toContainText("Se matriculó a «Andrea Zelaya».");
  });

  test("the form refuses to send without a student and a section, and names each one", async ({ page, scenarioId }) => {
    await openEnrollments(page, scenarioId);
    await page.getByRole("button", { name: "Matricular", exact: true }).click();

    await submitEnroll(page).click();

    await expect(dialog(page).getByRole("group", { name: "Hay 2 campos con errores." })).toBeVisible();
    await expect(dialog(page).getByRole("combobox", { name: /Buscar estudiante/ })).toHaveAttribute("aria-invalid", "true");
    await expect(field(page, "Sección")).toHaveAttribute("aria-invalid", "true");
  });

  test("an exit without a reason is refused before sending, a graduation does not need one, and a reason of two characters is refused", async ({ page, scenarioId }) => {
    await openEnrollments(page, scenarioId);
    await page.getByRole("button", { name: "Registrar salida de Ana Aguilar" }).click();

    await dialog(page).getByRole("button", { name: "Registrar salida" }).click();

    await expect(dialog(page).getByRole("group", { name: "Hay 1 campo con error." })).toBeVisible();
    await expect(field(page, "Motivo")).toHaveAccessibleDescription(/Este dato es obligatorio\./);
    await field(page, "Motivo").fill("ab");
    await dialog(page).getByRole("button", { name: "Registrar salida" }).click();
    await expect(field(page, "Motivo")).toHaveAccessibleDescription(/Escriba un motivo de 3 a 500 caracteres\./);

    await field(page, "Tipo de salida").selectOption("GRADUATED");
    await field(page, "Motivo").fill("");
    await expect(field(page, "Motivo")).not.toHaveAttribute("aria-required", "true");
    await dialog(page).getByRole("button", { name: "Registrar salida" }).click();

    await expect(dialog(page)).toBeHidden({ timeout: 15_000 });
    await expect(page.getByRole("row", { name: /Aguilar, Ana/ })).toContainText("Graduada");
  });

  test("moving to a full section says so in Spanish and leaves the dialog open with the choice", async ({ page, scenarioId }) => {
    await openEnrollments(page, scenarioId);
    // Mateo Lagos is in "Primero A", on the second page: the section filter brings the row up.
    await page.getByLabel("Sección", { exact: true }).selectOption(SECTION_IDS.firstA);
    await page.getByRole("button", { name: "Trasladar de sección a Mateo Lagos" }).click();
    await field(page, "Sección nueva").selectOption(SECTION_IDS.secondA);
    await field(page, "Fecha del traslado").fill(ENROLL_DATE);

    await dialog(page).getByRole("button", { name: "Trasladar" }).click();

    await expect(alertOf(page)).toContainText("La sección ya alcanzó su cupo.");
    await expect(alertOf(page)).toBeFocused();
    await expect(field(page, "Sección nueva")).toHaveValue(SECTION_IDS.secondA);
  });
});

test.describe("a refusal for lack of permission", () => {
  test("a 403 on a write is told in plain words in the dialog, and nothing changes on the screen", async ({ page, scenarioId }) => {
    await openEnrollments(page, scenarioId, "enrollments-forbidden");
    await fillEnrollment(page, { ...ZELAYA, sectionId: SECTION_IDS.firstB, date: ENROLL_DATE });

    await submitEnroll(page).click();

    await expect(alertOf(page)).toContainText("No tiene permiso para realizar esta acción.");
    await expect(dialog(page)).toBeVisible();
    await page.getByRole("button", { name: "Cancelar" }).click();
    await expect(page.getByText("de 37")).toBeVisible();
  });
});

test.describe("a list with nothing in it, and a list that does not load", () => {
  test("shows the empty state with its way forward, which opens the form", async ({ page, scenarioId }) => {
    await openEnrollments(page, scenarioId, "enrollments-empty");

    await expect(page.getByText("Todavía no hay matrículas")).toBeVisible();
    await expect.poll(() => bodyRows(page)).toHaveLength(1);

    await page.getByRole("button", { name: "Matricular a un estudiante" }).click();

    await expect(dialog(page).getByRole("heading", { name: "Matricular estudiante" })).toBeVisible();
    await page.getByRole("button", { name: "Cancelar" }).click();
    await expect(page.getByRole("button", { name: "Matricular a un estudiante" })).toBeFocused();
  });

  test("shows the error state with the trace id when the list fails, and Retry loads it", async ({ page, scenarioId }) => {
    // The page retries a read twice by itself (1 s and 2 s), and the simulator fails exactly those three reads.
    test.setTimeout(60_000);
    await openEnrollments(page, scenarioId, "enrollments-server-error");

    await expect(page.getByText("No se pudo cargar la lista de matrículas")).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeVisible();

    await page.getByRole("button", { name: "Reintentar" }).click();

    await expect(page.getByText("No se pudo cargar la lista de matrículas")).toBeHidden();
    await expect.poll(() => bodyRows(page).then((rows) => rows.length)).toBe(25);
  });

  test("the search of the student asks the server only from two characters", async ({ page, scenarioId }) => {
    await openEnrollments(page, scenarioId);
    const searches: string[] = [];
    page.on("request", (request) => {
      const url = new URL(request.url());
      if (url.pathname === "/api/v1/students") searches.push(decodeURIComponent(url.search));
    });
    await page.getByRole("button", { name: "Matricular", exact: true }).click();

    await dialog(page).getByRole("combobox", { name: /Buscar estudiante/ }).fill("Z");
    await page.waitForTimeout(800);
    expect(searches).toEqual([]);
    await expect(dialog(page).getByText("Escriba al menos 2 caracteres para buscar.", { exact: true })).toBeVisible();

    await dialog(page).getByRole("combobox", { name: /Buscar estudiante/ }).fill("Ze");

    await expect(dialog(page).getByRole("option", { name: ZELAYA.option })).toBeVisible();
    expect(searches).toHaveLength(1);
    expect(searches[0]).toContain("q=Ze");
    expect(searches[0]).toContain("status=ACTIVE");
  });
});
