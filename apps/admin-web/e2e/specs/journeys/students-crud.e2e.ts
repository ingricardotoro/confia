import { randomUUID } from "node:crypto";
import { studentCodeOf } from "../../mock-api/students-seed.ts";
import {
  alertOf,
  bodyRows,
  dialog,
  field,
  openCreate,
  openStudents,
  recordStudentResponses,
  resultNotice,
  save,
  SAVE_TIMEOUT,
  searchBox,
  submitStudent,
  test,
} from "../../support/students.ts";
import { expect, recordedCalls } from "../../support/scenario.ts";

// The students registry through its screen (students-registry cut): sign in, open "Estudiantes" from the navigation,
// register a student with a document and find them by last name, edit, replace and remove the document, and
// deactivate and activate. Real Chromium, the production build and the SIMULATED API: a simulation of the published
// shapes, not a test of the real stack or of its rules.
const IDENTIFIER = "docente.prueba@example.test";
/** The document typed in the journeys: it must not come back in any response, nor appear in the page or the URL. */
const DOCUMENT = "0801-2010-98765";
const DOCUMENT_DIGITS = "0801201098765";

test.describe("the students registry, registering and editing", () => {
  test("a person signs in, opens 'Estudiantes' from the navigation and lands on the list ordered by last name", async ({ page }) => {
    await page.goto("/iniciar-sesion");
    await page.locator("#sign-in-identifier").fill(IDENTIFIER);
    // Made at run time, so no password is written here.
    await page.locator("#sign-in-password").fill(randomUUID());
    await page.getByRole("button", { name: "Iniciar sesión" }).click();
    await expect(page.getByRole("heading", { level: 1, name: "Sesión iniciada." })).toBeVisible();

    await page.getByRole("navigation", { name: "Navegación principal" }).getByRole("link", { name: "Estudiantes" }).click();

    await expect(page).toHaveURL(/\/students$/);
    await expect(page.getByRole("heading", { level: 1, name: "Estudiantes" })).toBeVisible();
    await expect(page.getByRole("link", { name: "Estudiantes" })).toHaveAttribute("aria-current", "page");
    await expect(page.getByRole("table", { name: "Estudiantes de la institución" })).toBeVisible();
    await expect(page.getByRole("columnheader", { name: "Apellidos" })).toHaveAttribute("aria-sort", "ascending");
    await expect.poll(async () => (await bodyRows(page))[0]?.slice(0, 3)).toEqual(["E-000001", "Aguilar", "Ana"]);
  });

  test("registers a student with a document, finds them by last name, and the document never comes back", async ({ page, scenarioId }) => {
    await openStudents(page, scenarioId);
    const answers = recordStudentResponses(page);
    await openCreate(page, "Nuevo estudiante");
    await expect(field(page, "Código")).toHaveAccessibleDescription("Si lo deja vacío, el sistema genera uno.");

    await submitStudent(page, {
      firstName: "Marta",
      lastName: "Zúñiga",
      birthDate: "2012-05-20",
      sex: "Femenino",
      document: DOCUMENT,
    });

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Se creó el registro «Marta Zúñiga».");
    // The document left the form with the dialog: nowhere in the page, nor in the address.
    expect(await page.content()).not.toContain(DOCUMENT);
    expect(await page.content()).not.toContain(DOCUMENT_DIGITS);
    expect(page.url()).not.toContain(DOCUMENT_DIGITS);

    await searchBox(page).fill("Zúñiga");

    await expect(page).toHaveURL(/q=Z%C3%BA%C3%B1iga/);
    await expect.poll(() => bodyRows(page)).toHaveLength(1);
    const [row] = await bodyRows(page);
    // The system generated the code (60 students were in the seed), and the document column only says it is registered.
    expect(row?.slice(0, 3)).toEqual([studentCodeOf(61), "Zúñiga", "Marta"]);
    expect(row?.[5]).toContain("Registrado");
    expect(row?.[5]).not.toMatch(/\d{4}/);
    expect(row?.[6]).toBe("Activo");
    for (const text of await Promise.all(answers)) expect(text).not.toContain(DOCUMENT_DIGITS);
    for (const text of await Promise.all(answers)) expect(text).not.toContain(DOCUMENT);
  });

  test("registers a student with a code of their own and without a document, which shows as a dash", async ({ page, scenarioId }) => {
    await openStudents(page, scenarioId);
    await openCreate(page, "Nuevo estudiante");

    await submitStudent(page, { code: "MI-001", firstName: "Omar", lastName: "Zapata", birthDate: "2013-02-02", sex: "Masculino" });

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await searchBox(page).fill("MI-001");
    await expect.poll(() => bodyRows(page)).toHaveLength(1);
    const [row] = await bodyRows(page);
    expect(row?.slice(0, 3)).toEqual(["MI-001", "Zapata", "Omar"]);
    expect(row?.[5]).toContain("—");
  });

  test("refuses an empty form before sending and names each field", async ({ page, request, scenarioId }) => {
    await openStudents(page, scenarioId, "students-empty");
    await openCreate(page, "Nuevo estudiante");

    await save(page).click();

    await expect(dialog(page).getByRole("group", { name: "Hay 4 campos con errores." })).toBeVisible();
    await expect(field(page, "Nombres")).toHaveAccessibleDescription(/Este dato es obligatorio\./);
    const writes = (await recordedCalls(request, scenarioId)).filter((call) => call.method !== "GET" && call.path.startsWith("/api/v1/students"));
    expect(writes).toEqual([]);
  });

  test("edits a student: the code is fixed, the document only shows as registered, and keeping it sends no document", async ({ page, scenarioId }) => {
    await openStudents(page, scenarioId);
    await searchBox(page).fill("Pérez");
    await expect.poll(() => bodyRows(page)).toHaveLength(2);

    await page.getByRole("button", { name: "Editar Luis Pérez" }).click();

    await expect(field(page, "Código")).toHaveValue(studentCodeOf(42));
    await expect(field(page, "Código")).toHaveAttribute("readonly", "");
    await expect(field(page, "Documento actual")).toHaveValue("Registrado");
    await expect(field(page, "Reemplazar documento")).toHaveValue("");
    await field(page, "Nombres").fill("Luis Alberto");
    await save(page).click();

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Se guardaron los cambios de «Luis Alberto Pérez».");
    const row = page.getByRole("row", { name: /Luis Alberto/ });
    await expect(row).toContainText("Registrado");
    await expect(row).toContainText("Activo");
  });

  test("replaces the document with a new one, and removes it only with the explicit box", async ({ page, scenarioId }) => {
    await openStudents(page, scenarioId);
    await searchBox(page).fill("Pérez");
    const row = page.getByRole("row", { name: /Luis Pérez/ });
    await expect(row).toContainText("Registrado");

    // Replace.
    await page.getByRole("button", { name: "Editar Luis Pérez" }).click();
    await field(page, "Reemplazar documento").fill(DOCUMENT);
    await save(page).click();
    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(row).toContainText("Registrado");
    expect(await page.content()).not.toContain(DOCUMENT_DIGITS);

    // Remove: the box is checked on purpose; an empty replacement alone would have kept it.
    await page.getByRole("button", { name: "Editar Luis Pérez" }).click();
    await expect(field(page, "Reemplazar documento")).toHaveValue("");
    await dialog(page).getByRole("checkbox", { name: "Quitar documento" }).check();
    await expect(field(page, "Reemplazar documento")).toBeDisabled();
    await save(page).click();
    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(row).toContainText("—");
    await expect(row).not.toContainText(/registrado/i);

    // A student with no document has no box to remove it.
    await page.getByRole("button", { name: "Editar Luis Pérez" }).click();
    await expect(field(page, "Documento actual")).toHaveValue("Sin documento");
    await expect(dialog(page).getByRole("checkbox", { name: "Quitar documento" })).toHaveCount(0);
  });

  test("deactivates and activates a student from the row", async ({ page, scenarioId }) => {
    await openStudents(page, scenarioId);
    await searchBox(page).fill("Mejía");
    await expect.poll(() => bodyRows(page)).toHaveLength(2);

    await page.getByRole("button", { name: "Desactivar Gabriela Mejía" }).click();
    await expect(resultNotice(page)).toContainText("Se desactivó «Gabriela Mejía».");
    await expect(page.getByRole("row", { name: /Gabriela Mejía/ })).toContainText("Inactivo");
    await expect(page.getByRole("button", { name: "Activar Gabriela Mejía" })).toBeVisible();
    // Nothing is deleted: the document of the row is still there.
    await expect(page.getByRole("row", { name: /Gabriela Mejía/ })).toContainText("Registrado");

    await page.getByRole("button", { name: "Activar Gabriela Mejía" }).click();
    await expect(resultNotice(page)).toContainText("Se activó «Gabriela Mejía».");
    await expect(page.getByRole("row", { name: /Gabriela Mejía/ })).toContainText("Activo");
  });

  test("shows an error of the server under its field: a birth date before 1990 or after today", async ({ page, scenarioId }) => {
    await openStudents(page, scenarioId, "students-empty");
    await openCreate(page, "Nuevo estudiante");

    await submitStudent(page, { firstName: "Ana", lastName: "Vieja", birthDate: "1989-12-31", sex: "Femenino" });
    await expect(dialog(page).getByRole("group", { name: "Hay 1 campo con error." })).toBeVisible();
    await expect(field(page, "Fecha de nacimiento")).toHaveAccessibleDescription("La fecha de nacimiento no puede ser anterior al 1 de enero de 1990.");

    await field(page, "Fecha de nacimiento").fill("2999-01-01");
    await save(page).click();
    await expect(field(page, "Fecha de nacimiento")).toHaveAccessibleDescription("La fecha de nacimiento no puede ser posterior a hoy.");
    await expect(alertOf(page)).toHaveCount(0);

    await field(page, "Fecha de nacimiento").fill("2012-05-20");
    await save(page).click();
    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Se creó el registro «Ana Vieja».");
  });
});
