import type { Page } from "@playwright/test";
import {
  applyPreset,
  dialog,
  field,
  firstColumn,
  itemRows,
  openCreate,
  openPlanDetail,
  openPlans,
  resultNotice,
  save,
  SAVE_TIMEOUT,
  SEEDED_PLANS,
  test,
} from "../../support/charges.ts";
import { expect, recordedCalls, seedSession } from "../../support/scenario.ts";

// What the screen says when the answer is not a plain yes: a business rule of the server (a plan with no items, a plan that is
// no longer a draft, a repeated concept, a record that changed under the editor), a refusal for lack of permission, a list or a
// detail with nothing in it, and one that fails to load. Texts are the Spanish ones a person reads. Against the SIMULATED API,
// which repeats only the rules these journeys need: the real rules are the backend's.
const ADD_ITEM = "Agregar concepto";
const alert = (page: Page) => dialog(page).getByRole("alert");

async function fillItem(page: Page, values: { concept: string; periods: string; first: string }): Promise<void> {
  await field(page, "Concepto").selectOption({ label: values.concept });
  await field(page, "Cantidad de períodos").fill(values.periods);
  await field(page, "Primer día del primer período").fill(values.first);
}

test.describe("a rule of the server refuses a change", () => {
  test("a plan with no items cannot be activated: the person is told, and the plan stays a draft", async ({ page, scenarioId }) => {
    await openPlanDetail(page, scenarioId, SEEDED_PLANS.draftEmpty, "Secundaria 2026");

    await page.getByRole("button", { name: "Activar plan Secundaria 2026" }).click();
    await dialog(page).getByRole("button", { name: "Activar plan" }).click();

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("El plan no tiene conceptos, por lo que no se puede activar.");
    await expect(page.getByRole("button", { name: "Activar plan Secundaria 2026" })).toBeVisible();
    await expect(page.getByLabel("Datos del plan")).toContainText("Borrador");
  });

  test("a first day that is not the first of a month is refused before sending, with a message that says what to do, and a valid one is saved", async ({ page, request, scenarioId }) => {
    await openPlanDetail(page, scenarioId, SEEDED_PLANS.draftEmpty, "Secundaria 2026");
    await page.getByRole("button", { name: ADD_ITEM }).click();
    await fillItem(page, { concept: "Matrícula anual", periods: "1", first: "2026-02-15" });

    await dialog(page).getByRole("button", { name: ADD_ITEM }).click();

    await expect(field(page, "Primer día del primer período")).toHaveAccessibleDescription(/Elija el primer día de un mes, por ejemplo el 1 de febrero\./);
    const writes = (await recordedCalls(request, scenarioId)).filter((call) => call.method === "POST" && call.path.endsWith("/items"));
    expect(writes).toEqual([]);

    await field(page, "Primer día del primer período").fill("2026-02-01");
    await dialog(page).getByRole("button", { name: ADD_ITEM }).click();

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect.poll(() => itemRows(page, "Conceptos del plan Secundaria 2026")).toEqual([["MAT — Matrícula anual", "1", "1 feb 2026", "Quitar"]]);
  });

  test("0 and 37 periods are refused before sending, with the range in the message", async ({ page, request, scenarioId }) => {
    await openPlanDetail(page, scenarioId, SEEDED_PLANS.draftEmpty, "Secundaria 2026");
    await page.getByRole("button", { name: ADD_ITEM }).click();

    for (const periods of ["0", "37"]) {
      await fillItem(page, { concept: "Matrícula anual", periods, first: "2026-02-01" });
      await dialog(page).getByRole("button", { name: ADD_ITEM }).click();
      await expect(field(page, "Cantidad de períodos")).toHaveAccessibleDescription(/entre 1 y 36/);
    }

    const writes = (await recordedCalls(request, scenarioId)).filter((call) => call.method === "POST" && call.path.endsWith("/items"));
    expect(writes).toEqual([]);
  });

  test("a frozen plan keeps its items: no button adds or removes one, and the plan says why", async ({ page, scenarioId }) => {
    await openPlanDetail(page, scenarioId, SEEDED_PLANS.active, "Primaria 2026");

    await expect(page.getByText(/Este plan está activo y congelado/)).toBeVisible();
    await expect(page.getByRole("button", { name: ADD_ITEM })).toHaveCount(0);
    await expect(page.getByRole("button", { name: /^Quitar/ })).toHaveCount(0);
    await page.getByRole("button", { name: "Editar plan Primaria 2026" }).click();
    await expect(field(page, "Periodicidad")).toBeDisabled();
    await expect(field(page, "Día de vencimiento")).toBeDisabled();
    await expect(field(page, "Nombre")).toBeEnabled();
  });

  test("an archived plan is for reading: the page says so and offers no action", async ({ page, scenarioId }) => {
    await openPlanDetail(page, scenarioId, SEEDED_PLANS.archived, "Sección A de primero 2026");

    await expect(page.getByText(/Este plan está archivado/)).toBeVisible();
    await expect(page.getByRole("button", { name: /Editar|Activar|Archivar|Agregar|Quitar/ })).toHaveCount(0);
  });
});

test.describe("a record that changed since it was opened", () => {
  test("activating with a stale version is refused in the page, the plan is read again and the second try activates it", async ({ page, scenarioId }) => {
    await openPlanDetail(page, scenarioId, SEEDED_PLANS.draftWithItem, "Segundo grado 2026", "charges-stale-version");

    await page.getByRole("button", { name: "Activar plan Segundo grado 2026" }).click();
    await dialog(page).getByRole("button", { name: "Activar plan" }).click();

    await expect(resultNotice(page)).toContainText("El registro cambió desde que lo abrió y sus cambios no se guardaron.");
    await expect(page.getByLabel("Datos del plan")).toContainText("Borrador");
    await page.getByRole("button", { name: "Activar plan Segundo grado 2026" }).click();
    await dialog(page).getByRole("button", { name: "Activar plan" }).click();

    await expect(resultNotice(page)).toContainText("Se activó el plan «Segundo grado 2026».");
    await expect(page.getByLabel("Datos del plan")).toContainText("Activo");
  });

  test("an edit with a stale version is refused with the form as it was, and the second try is saved", async ({ page, scenarioId }) => {
    await openPlanDetail(page, scenarioId, SEEDED_PLANS.draftWithItem, "Segundo grado 2026", "charges-stale-version");
    await page.getByRole("button", { name: "Editar plan Segundo grado 2026" }).click();
    await field(page, "Nombre").fill("Segundo 2026");

    await dialog(page).getByRole("button", { name: "Guardar cambios" }).click();

    await expect(alert(page)).toContainText("El registro cambió desde que lo abrió y sus cambios no se guardaron.");
    await expect(field(page, "Nombre")).toHaveValue("Segundo 2026");
    await dialog(page).getByRole("button", { name: "Cancelar" }).click();
    await expect(dialog(page)).toBeHidden();
    await page.getByRole("button", { name: "Editar plan Segundo grado 2026" }).click();
    await field(page, "Nombre").fill("Segundo 2026");
    await dialog(page).getByRole("button", { name: "Guardar cambios" }).click();

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Se guardaron los cambios del plan «Segundo 2026».");
  });
});

test.describe("a person without the permission", () => {
  test("is told so plainly when a new plan is refused with 403, and nothing changes", async ({ page, request, scenarioId }) => {
    await openPlans(page, scenarioId, "charges-forbidden");
    await openCreate(page, "Nuevo plan de cobro");
    await field(page, "Nombre").fill("Primaria 2027");
    await field(page, "Año lectivo").selectOption({ label: "Año lectivo 2026" });
    await field(page, "Grado").selectOption({ label: "Primero" });
    await field(page, "Día de vencimiento").fill("5");

    await save(page).click();

    await expect(alert(page)).toContainText("No tiene permiso para realizar esta acción.");
    await dialog(page).getByRole("button", { name: "Cancelar" }).click();
    await expect.poll(() => firstColumn(page)).toHaveLength(25);
    const writes = (await recordedCalls(request, scenarioId)).filter((call) => call.method === "POST" && call.path === "/api/v1/charges/plans");
    expect(writes).toHaveLength(1);
  });

  test("is told so when an item, the activation or the removal is refused, and the plan keeps its state", async ({ page, scenarioId }) => {
    await openPlanDetail(page, scenarioId, SEEDED_PLANS.draftWithItem, "Segundo grado 2026", "charges-forbidden");
    await page.getByRole("button", { name: ADD_ITEM }).click();
    await fillItem(page, { concept: "Matrícula anual", periods: "1", first: "2026-01-01" });
    await dialog(page).getByRole("button", { name: ADD_ITEM }).click();
    await expect(alert(page)).toContainText("No tiene permiso para realizar esta acción.");
    await dialog(page).getByRole("button", { name: "Cancelar" }).click();

    await page.getByRole("button", { name: "Quitar Colegiatura del plan" }).click();
    await dialog(page).getByRole("button", { name: "Quitar concepto" }).click();
    await expect(resultNotice(page)).toContainText("No tiene permiso para realizar esta acción.");

    await page.getByRole("button", { name: "Activar plan Segundo grado 2026" }).click();
    await dialog(page).getByRole("button", { name: "Activar plan" }).click();

    await expect(resultNotice(page)).toContainText("No tiene permiso para realizar esta acción.");
    await expect(page.getByLabel("Datos del plan")).toContainText("Borrador");
    await expect.poll(() => itemRows(page, "Conceptos del plan Segundo grado 2026")).toHaveLength(1);
  });
});

test.describe("lists with nothing in them, and lists that do not load", () => {
  test("shows the empty state of the plans with its way forward, which opens the form", async ({ page, scenarioId }) => {
    await openPlans(page, scenarioId, "charges-empty");

    await expect(page.getByText("Todavía no hay planes de cobro")).toBeVisible();
    await page.getByRole("button", { name: "Crear el primer plan" }).click();
    await expect(dialog(page).getByRole("heading", { name: "Nuevo plan de cobro" })).toBeVisible();
    await dialog(page).getByRole("button", { name: "Cancelar" }).click();
    await expect(dialog(page)).toBeHidden();
  });

  test("shows the empty state of a plan with no items, with its way forward, which opens the form of an item", async ({ page, scenarioId }) => {
    await openPlanDetail(page, scenarioId, SEEDED_PLANS.draftEmpty, "Secundaria 2026");

    await expect(page.getByText("Este plan todavía no tiene conceptos")).toBeVisible();
    await page.getByRole("button", { name: "Agregar el primer concepto" }).click();

    await expect(dialog(page).getByRole("heading", { name: ADD_ITEM })).toBeVisible();
  });

  test("shows the error state of the plans with the trace id when they fail, and Retry loads them", async ({ page, scenarioId }) => {
    // The page retries a read twice by itself (1 s and 2 s), and the simulator fails exactly those three reads.
    test.setTimeout(60_000);
    await openPlans(page, scenarioId, "charges-server-error");

    await expect(page.getByText("No se pudieron cargar los planes de cobro")).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeVisible();

    await page.getByRole("button", { name: "Reintentar" }).click();

    await expect(page.getByText("No se pudieron cargar los planes de cobro")).toBeHidden();
    await expect.poll(() => firstColumn(page).then((names) => names.length)).toBe(25);
  });

  test("shows the error state of a detail that fails, and Retry loads it", async ({ page, scenarioId }) => {
    test.setTimeout(60_000);
    await applyPreset(page, scenarioId, "charges-server-error");
    await seedSession(page);
    await page.goto(`/charges/plans/${SEEDED_PLANS.active}`);

    await expect(page.getByText("No se pudo cargar el plan de cobro")).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeVisible();

    await page.getByRole("button", { name: "Reintentar" }).click();

    await expect(page.getByRole("heading", { level: 2, name: "Primaria 2026" })).toBeVisible();
    await expect.poll(() => itemRows(page, "Conceptos del plan Primaria 2026")).toHaveLength(2);
  });

  test("tells a plan that does not exist as not found, with a way back", async ({ page, scenarioId }) => {
    await openPlans(page, scenarioId);

    await page.goto("/charges/plans/00000000-0000-4000-8000-000000000001");

    await expect(page.getByText("No se pudo cargar el plan de cobro")).toBeVisible({ timeout: 20_000 });
    await expect(page.getByRole("link", { name: "Volver a los planes de cobro" })).toBeVisible();
  });
});
