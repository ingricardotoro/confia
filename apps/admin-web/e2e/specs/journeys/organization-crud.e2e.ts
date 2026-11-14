import { randomUUID } from "node:crypto";
import { yearName } from "../../mock-api/organization-seed.ts";
import {
  applyPreset,
  bodyRows,
  dialog,
  field,
  firstColumn,
  goToView,
  openCreate,
  openOrganization,
  openView,
  resultNotice,
  save,
  SAVE_TIMEOUT,
  test,
} from "../../support/organization.ts";
import { expect, recordedCalls } from "../../support/scenario.ts";

// The academic structure through its screens (organization-academic-structure cut): sign in, open the area from the
// navigation, create a year, a modality, a grade and a section, and edit and deactivate. Real Chromium, the production
// build, and the SIMULATED API: a simulation of the published shapes, not a test of the real stack or of its rules.
const IDENTIFIER = "docente.prueba@example.test";

test.describe("the academic structure, creating and editing", () => {
  test("a person signs in, opens 'Estructura académica' from the navigation and lands on the years", async ({ page }) => {
    await page.goto("/iniciar-sesion");
    await page.locator("#sign-in-identifier").fill(IDENTIFIER);
    // Made at run time, so no password is written here.
    await page.locator("#sign-in-password").fill(randomUUID());
    await page.getByRole("button", { name: "Iniciar sesión" }).click();
    await expect(page.getByRole("heading", { level: 1, name: "Sesión iniciada." })).toBeVisible();

    await page.getByRole("navigation", { name: "Navegación principal" }).getByRole("link", { name: "Estructura académica" }).click();

    await expect(page).toHaveURL(/\/organization\/academic-years$/);
    await expect(page.getByRole("heading", { level: 1, name: "Estructura académica" })).toBeVisible();
    await expect(page.getByRole("heading", { level: 2, name: "Años lectivos" })).toBeVisible();
    await expect(page.getByRole("link", { name: "Años lectivos" })).toHaveAttribute("aria-current", "page");
    await expect(page.getByRole("table", { name: "Años lectivos de la institución" })).toBeVisible();
    await expect.poll(() => firstColumn(page)).toContain(yearName(2026));
  });

  test("creates a year, a modality, a grade and a section from an empty institution and sees each one listed", async ({ page, scenarioId }) => {
    // Four screens, four dialogs and the navigation between them: longer than the default 30 seconds on a loaded machine.
    test.setTimeout(90_000);
    await applyPreset(page, scenarioId, "organization-empty");
    await openOrganization(page);
    await expect(page.getByText("Todavía no hay años lectivos")).toBeVisible();

    // Year.
    await openCreate(page, "Nuevo año lectivo");
    await field(page, "Nombre").fill("Año lectivo 2027");
    await field(page, "Fecha de inicio").fill("2027-01-15");
    await field(page, "Fecha de fin").fill("2027-11-30");
    await save(page).click();
    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Se creó el registro «Año lectivo 2027».");
    await expect.poll(() => bodyRows(page)).toEqual([["Año lectivo 2027", "15 ene 2027", "30 nov 2027", "Planificado", "Activo", "EditarDesactivar"]]);

    // Modality.
    await goToView(page, "modalities");
    await expect(page.getByText("Todavía no hay modalidades")).toBeVisible();
    await openCreate(page, "Nueva modalidad");
    await field(page, "Código").fill("PRI");
    await field(page, "Nombre").fill("Primaria");
    await save(page).click();
    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Se creó el registro «Primaria».");
    await expect.poll(async () => (await bodyRows(page)).map((cells) => cells.slice(0, 2))).toEqual([["PRI", "Primaria"]]);

    // Grade, of that modality.
    await goToView(page, "grades");
    await expect(page.getByText("Todavía no hay grados")).toBeVisible();
    await openCreate(page, "Nuevo grado");
    await field(page, "Modalidad").selectOption({ label: "Primaria" });
    await field(page, "Nombre").fill("Primero");
    await field(page, "Orden").fill("1");
    await save(page).click();
    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Se creó el registro «Primero».");
    await expect.poll(async () => (await bodyRows(page)).map((cells) => cells.slice(0, 3))).toEqual([["Primero", "1", "Primaria"]]);

    // Section, of that year and grade, with no capacity.
    await goToView(page, "sections");
    await expect(page.getByText("Todavía no hay secciones")).toBeVisible();
    await openCreate(page, "Nueva sección");
    await field(page, "Año lectivo").selectOption({ label: "Año lectivo 2027" });
    await field(page, "Grado").selectOption({ label: "Primaria · Primero" });
    await field(page, "Nombre").fill("A");
    await save(page).click();
    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Se creó el registro «A».");
    await expect.poll(async () => (await bodyRows(page)).map((cells) => cells.slice(0, 4))).toEqual([["A", "Año lectivo 2027", "Primaria · Primero", "Sin cupo definido"]]);
  });

  test("shows the field errors of an empty form and sends nothing to the API", async ({ page, request, scenarioId }) => {
    await openView(page, scenarioId, "modalities", "organization-empty");
    await openCreate(page, "Nueva modalidad");

    await save(page).click();

    await expect(dialog(page).getByRole("group", { name: "Hay 2 campos con errores." })).toBeVisible();
    await expect(field(page, "Código")).toHaveAccessibleDescription(/Este dato es obligatorio\./);
    const writes = (await recordedCalls(request, scenarioId)).filter((call) => call.method !== "GET" && call.path.startsWith("/api/v1/organization"));
    expect(writes).toEqual([]);
  });

  test("edits a modality, and deactivates and activates it from its row", async ({ page, scenarioId }) => {
    await openView(page, scenarioId, "modalities");

    await page.getByRole("button", { name: "Editar Primaria" }).click();
    await expect(field(page, "Código")).toHaveValue("PRI");
    await field(page, "Nombre").fill("Primaria básica");
    await save(page).click();
    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Se guardaron los cambios de «Primaria básica».");
    await expect.poll(() => firstColumn(page)).toEqual(["PRE", "PRI", "SEC"]);
    await expect(page.getByRole("row", { name: /Primaria básica/ })).toContainText("Activo");

    await page.getByRole("button", { name: "Desactivar Primaria básica" }).click();
    await expect(resultNotice(page)).toContainText("Se desactivó «Primaria básica».");
    await expect(page.getByRole("row", { name: /Primaria básica/ })).toContainText("Inactivo");
    await expect(page.getByRole("button", { name: "Activar Primaria básica" })).toBeVisible();

    await page.getByRole("button", { name: "Activar Primaria básica" }).click();
    await expect(resultNotice(page)).toContainText("Se activó «Primaria básica».");
    await expect(page.getByRole("row", { name: /Primaria básica/ })).toContainText("Activo");
  });

  test("edits a year with its dates, and shows what is fixed after creation as read only on a grade", async ({ page, scenarioId }) => {
    await openView(page, scenarioId, "years");
    await page.getByRole("button", { name: `Editar ${yearName(2026)}` }).click();
    await field(page, "Fecha de fin").fill("2026-12-15");
    await field(page, "Etapa").selectOption({ label: "Planificado" });
    await save(page).click();
    await expect(resultNotice(page)).toContainText("Se guardaron los cambios");
    await expect(page.getByRole("row", { name: new RegExp(yearName(2026)) })).toContainText("15 dic 2026");

    await goToView(page, "grades");
    await page.getByRole("button", { name: "Editar Primero" }).click();
    await expect(field(page, "Modalidad")).toHaveValue("Primaria");
    await expect(field(page, "Modalidad")).toHaveAttribute("readonly", "");
    await field(page, "Orden").fill("1");
    await field(page, "Nombre").fill("Primer grado");
    await save(page).click();
    await expect(resultNotice(page)).toContainText("Se guardaron los cambios de «Primer grado».");
    await page.getByRole("button", { name: "Desactivar Primer grado" }).click();
    await expect(page.getByRole("row", { name: /Primer grado/ })).toContainText("Inactivo");
  });

  test("edits a section and clears its capacity, which is sent as no capacity", async ({ page, scenarioId }) => {
    await openView(page, scenarioId, "sections");
    const row = page.getByRole("row", { name: /Segundo/ });
    await expect(row).toContainText("25");

    await row.getByRole("button", { name: "Editar A" }).click();
    await expect(field(page, "Cupo")).toHaveValue("25");
    await field(page, "Cupo").fill("");
    await save(page).click();

    await expect(resultNotice(page)).toContainText("Se guardaron los cambios de «A».");
    await expect(row).toContainText("Sin cupo definido");
  });
});
