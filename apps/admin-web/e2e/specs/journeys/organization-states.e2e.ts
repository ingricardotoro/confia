import { yearName } from "../../mock-api/organization-seed.ts";
import {
  bodyRows,
  dialog,
  field,
  firstColumn,
  goToView,
  openCreate,
  openView,
  resultNotice,
  save,
  SAVE_TIMEOUT,
  test,
} from "../../support/organization.ts";
import { expect, recordedCalls } from "../../support/scenario.ts";

// What the screens say when the answer is not a plain yes: a business rule of the server, a refusal for lack of
// permission, a list with nothing in it and a list that fails to load. Texts are the Spanish ones a person reads.
// Against the SIMULATED API, which repeats only the rules these journeys need: the real rules are the backend's.
const alert = (page: import("@playwright/test").Page) => dialog(page).getByRole("alert");

test.describe("a rule of the server refuses a save", () => {
  test("a year that overlaps another by one day is refused in Spanish, the dialog stays open, and the corrected dates are saved", async ({ page, scenarioId }) => {
    await openView(page, scenarioId, "years");
    await openCreate(page, "Nuevo año lectivo");
    await field(page, "Nombre").fill("Año lectivo 2027");
    // The year in the seed ends on 30 November 2026, and the end of a year counts as part of it.
    await field(page, "Fecha de inicio").fill("2026-11-30");
    await field(page, "Fecha de fin").fill("2027-10-30");

    await save(page).click();

    await expect(alert(page)).toContainText("Las fechas se cruzan con las de otro año lectivo.");
    await expect(dialog(page)).toBeVisible();
    await expect.poll(() => firstColumn(page)).not.toContain("Año lectivo 2027");

    await field(page, "Fecha de inicio").fill("2026-12-01");
    await save(page).click();

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Se creó el registro «Año lectivo 2027».");
    await expect(page.getByRole("row", { name: /Año lectivo 2027/ })).toBeVisible();
  });

  test("a repeated modality code is refused with the message of a duplicate, and Cancel leaves the list as it was", async ({ page, scenarioId }) => {
    await openView(page, scenarioId, "modalities");
    await openCreate(page, "Nueva modalidad");
    await field(page, "Código").fill("PRI");
    await field(page, "Nombre").fill("Otra primaria");

    await save(page).click();

    await expect(alert(page)).toContainText("Ya existe un registro con ese nombre, código u orden. Escriba un valor distinto.");
    await dialog(page).getByRole("button", { name: "Cancelar" }).click();
    await expect(dialog(page)).toBeHidden();
    await expect.poll(() => firstColumn(page)).toEqual(["PRE", "PRI", "SEC"]);
  });

  test("a repeated grade order is refused the same way, and a second year in progress with its own message", async ({ page, scenarioId }) => {
    await openView(page, scenarioId, "grades");
    await openCreate(page, "Nuevo grado");
    await field(page, "Modalidad").selectOption({ label: "Primaria" });
    await field(page, "Nombre").fill("Cuarto");
    await field(page, "Orden").fill("1");
    await save(page).click();
    await expect(alert(page)).toContainText("Ya existe un registro con ese nombre, código u orden.");
    await dialog(page).getByRole("button", { name: "Cancelar" }).click();

    await goToView(page, "years");
    await openCreate(page, "Nuevo año lectivo");
    await field(page, "Nombre").fill("Año lectivo 2028");
    await field(page, "Fecha de inicio").fill("2028-01-15");
    await field(page, "Fecha de fin").fill("2028-11-30");
    await field(page, "Etapa").selectOption({ label: "En curso" });
    await save(page).click();

    await expect(alert(page)).toContainText("Ya hay otro año lectivo en curso.");
  });

  test("a closed year cannot be edited, and says so", async ({ page, scenarioId }) => {
    await openView(page, scenarioId, "years");
    // The oldest year is on the second page of the default order; the URL names the order that puts it first.
    await page.goto("/organization/academic-years?sort=name:asc");
    await page.getByRole("button", { name: `Editar ${yearName(1997)}` }).click();
    await expect.poll(() => field(page, "Nombre").count()).toBe(1);
    await field(page, "Nombre").fill("Año lectivo 1997 revisado");

    await save(page).click();

    await expect(alert(page)).toContainText("El año lectivo está cerrado.");
  });
});

test.describe("a person without the permission", () => {
  test("is told so plainly when a save is refused with 403, and nothing changes", async ({ page, request, scenarioId }) => {
    await openView(page, scenarioId, "modalities", "organization-forbidden");
    await openCreate(page, "Nueva modalidad");
    await field(page, "Código").fill("NEW");
    await field(page, "Nombre").fill("Nueva");

    await save(page).click();

    await expect(alert(page)).toContainText("No tiene permiso para realizar esta acción. Si la necesita para su trabajo, solicítela a un administrador de su institución.");
    await dialog(page).getByRole("button", { name: "Cancelar" }).click();
    await expect.poll(() => firstColumn(page)).toEqual(["PRE", "PRI", "SEC"]);
    const writes = (await recordedCalls(request, scenarioId)).filter((call) => call.method === "POST" && call.path.startsWith("/api/v1/organization"));
    expect(writes).toHaveLength(1);
  });

  test("is told so when deactivating from a row is refused, and the row keeps its state", async ({ page, scenarioId }) => {
    await openView(page, scenarioId, "modalities", "organization-forbidden");

    await page.getByRole("button", { name: "Desactivar Primaria" }).click();

    await expect(resultNotice(page)).toContainText("No tiene permiso para realizar esta acción.");
    await expect(page.getByRole("row", { name: /Primaria/ })).toContainText("Activo");
    await expect(page.getByRole("button", { name: "Desactivar Primaria" })).toBeVisible();
  });
});

test.describe("a list with nothing in it, and a list that does not load", () => {
  test("shows the empty state of each view with its way forward, which opens the form", async ({ page, scenarioId }) => {
    await openView(page, scenarioId, "years", "organization-empty");
    const views = [
      ["years", "Todavía no hay años lectivos", "Crear el primer año lectivo", "Nuevo año lectivo"],
      ["modalities", "Todavía no hay modalidades", "Crear la primera modalidad", "Nueva modalidad"],
      ["grades", "Todavía no hay grados", "Crear el primer grado", "Nuevo grado"],
      ["sections", "Todavía no hay secciones", "Crear la primera sección", "Nueva sección"],
    ] as const;

    for (const [view, title, action, formTitle] of views) {
      await goToView(page, view);
      await expect(page.getByText(title)).toBeVisible();
      await expect.poll(() => bodyRows(page)).toHaveLength(1);
      await page.getByRole("button", { name: action }).click();
      await expect(dialog(page).getByRole("heading", { name: formTitle })).toBeVisible();
      await dialog(page).getByRole("button", { name: "Cancelar" }).click();
      await expect(dialog(page)).toBeHidden();
    }
  });

  test("shows the error state with the trace id when the list fails, and Retry loads it", async ({ page, scenarioId }) => {
    // The page retries a read twice by itself (1 s and 2 s), and the simulator fails exactly those three reads.
    test.setTimeout(60_000);
    await openView(page, scenarioId, "years", "organization-server-error");

    await expect(page.getByText("No se pudo cargar la lista de años lectivos")).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeVisible();
    await expect.poll(() => bodyRows(page)).toHaveLength(1);

    await page.getByRole("button", { name: "Reintentar" }).click();

    await expect(page.getByText("No se pudo cargar la lista de años lectivos")).toBeHidden();
    await expect.poll(() => firstColumn(page).then((names) => names.length)).toBe(25);
    await expect.poll(() => firstColumn(page).then((names) => names[0])).toBe(yearName(2026));
  });
});
