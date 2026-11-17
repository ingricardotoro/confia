import type { Page } from "@playwright/test";
import {
  bodyRows,
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
import { expect, recordedCalls } from "../../support/scenario.ts";

// The charge plans through their screen (charges-plans cut): open «Planes de cobro» from «Cobros», create a plan, open its
// detail, add concepts, activate it and archive another. Real Chromium, the production build and the SIMULATED API: a
// simulation of the published shapes, not a test of the real stack or of its rules.
const ADD_ITEM = "Agregar concepto";

async function addItem(page: Page, values: { concept: string; periods: string; first: string }): Promise<void> {
  await page.getByRole("button", { name: ADD_ITEM }).click();
  await expect(dialog(page)).toBeVisible();
  await field(page, "Concepto").selectOption({ label: values.concept });
  await field(page, "Cantidad de períodos").fill(values.periods);
  await field(page, "Primer día del primer período").fill(values.first);
  await dialog(page).getByRole("button", { name: ADD_ITEM }).click();
}

test.describe("the plans, creating and listing", () => {
  test("opens «Planes de cobro» from «Cobros» in the navigation, and shows each plan in text with its year and target named", async ({ page, scenarioId }) => {
    await openPlans(page, scenarioId);

    await expect(page.getByRole("navigation", { name: "Navegación principal" }).getByRole("link", { name: "Cobros" })).toHaveAttribute("aria-current", "page");
    await expect(page.getByRole("table", { name: "Planes de cobro de la institución" })).toBeVisible();
    await expect.poll(() => firstColumn(page).then((names) => names.length)).toBe(25);
    await page.getByRole("group", { name: "Filtros" }).getByLabel("Ámbito").selectOption({ label: "Una sección" });
    await expect.poll(() => bodyRows(page)).toEqual([["Sección A de primero 2026", "Año lectivo 2026", "Una sección", "Primero A (Año lectivo 2026)", "Única vez", "Día 15", "Archivado"]]);
    await page.getByRole("group", { name: "Filtros" }).getByLabel("Ámbito").selectOption({ label: "Un grado" });
    await expect.poll(() => bodyRows(page)).toEqual([
      ["Primaria 2026", "Año lectivo 2026", "Un grado", "Primero", "Mensual", "Día 5", "Activo"],
      ["Segundo grado 2026", "Año lectivo 2026", "Un grado", "Segundo", "Mensual", "Día 5", "Borrador"],
    ]);
  });

  test("leads the address of the area to the plans", async ({ page, scenarioId }) => {
    await openPlans(page, scenarioId);

    await page.goto("/charges");

    await expect(page).toHaveURL(/\/charges\/plans$/);
    await expect(page.getByRole("heading", { level: 2, name: "Planes de cobro" })).toBeVisible();
  });

  test("creates 'Primaria 2027' for a grade, monthly with the due day 5, born a draft, from an empty list", async ({ page, scenarioId }) => {
    await openPlans(page, scenarioId, "charges-empty");
    await expect(page.getByText("Todavía no hay planes de cobro")).toBeVisible();

    await openCreate(page, "Nuevo plan de cobro");
    await expect(field(page, "Ámbito")).toHaveValue("GRADE");
    await expect(field(page, "Periodicidad")).toHaveValue("MONTHLY");
    await field(page, "Nombre").fill("Primaria 2027");
    await field(page, "Año lectivo").selectOption({ label: "Año lectivo 2026" });
    await field(page, "Grado").selectOption({ label: "Primero" });
    await field(page, "Día de vencimiento").fill("5");
    await save(page).click();

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Se creó el registro «Primaria 2027».");
    await expect.poll(() => bodyRows(page)).toEqual([["Primaria 2027", "Año lectivo 2026", "Un grado", "Primero", "Mensual", "Día 5", "Borrador"]]);
  });

  test("creates a plan for one section, naming it with its grade and year, and for one modality", async ({ page, scenarioId }) => {
    await openPlans(page, scenarioId, "charges-empty");
    await openCreate(page, "Nuevo plan de cobro");
    await field(page, "Nombre").fill("Sección B");
    await field(page, "Año lectivo").selectOption({ label: "Año lectivo 2026" });
    await field(page, "Ámbito").selectOption({ label: "Una sección" });
    await field(page, "Sección").selectOption({ label: "Primero B (Año lectivo 2026)" });
    await field(page, "Periodicidad").selectOption({ label: "Anual" });
    await field(page, "Día de vencimiento").fill("28");
    await save(page).click();
    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);

    await openCreate(page, "Nuevo plan de cobro");
    await field(page, "Nombre").fill("Secundaria");
    await field(page, "Año lectivo").selectOption({ label: "Año lectivo 2026" });
    await field(page, "Ámbito").selectOption({ label: "Una modalidad" });
    await field(page, "Modalidad").selectOption({ label: "Secundaria" });
    await field(page, "Día de vencimiento").fill("1");
    await save(page).click();
    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);

    await expect.poll(() => bodyRows(page)).toEqual([
      ["Sección B", "Año lectivo 2026", "Una sección", "Primero B (Año lectivo 2026)", "Anual", "Día 28", "Borrador"],
      ["Secundaria", "Año lectivo 2026", "Una modalidad", "Secundaria", "Mensual", "Día 1", "Borrador"],
    ]);
  });

  test("forgets the grade chosen before the scope changed", async ({ page, scenarioId }) => {
    await openPlans(page, scenarioId, "charges-empty");
    await openCreate(page, "Nuevo plan de cobro");
    await field(page, "Grado").selectOption({ label: "Séptimo" });

    await field(page, "Ámbito").selectOption({ label: "Una modalidad" });

    await expect(dialog(page).getByLabel(/^Grado/)).toHaveCount(0);
    await expect(field(page, "Modalidad")).toHaveValue("");
  });

  test("shows the field errors of an empty form and sends nothing to the API", async ({ page, scenarioId, request }) => {
    await openPlans(page, scenarioId);
    await openCreate(page, "Nuevo plan de cobro");

    await save(page).click();

    await expect(dialog(page).getByRole("group", { name: "Hay 4 campos con errores." })).toBeVisible();
    await expect(field(page, "Nombre")).toHaveAccessibleDescription(/Este dato es obligatorio\./);
    await expect(field(page, "Año lectivo")).toHaveAccessibleDescription(/Este dato es obligatorio\./);
    await expect(field(page, "Grado")).toHaveAccessibleDescription(/Este dato es obligatorio\./);
    await expect(field(page, "Día de vencimiento")).toHaveAccessibleDescription(/Este dato es obligatorio\./);
    const writes = (await recordedCalls(request, scenarioId)).filter((call) => call.method !== "GET" && call.path.startsWith("/api/v1/charges"));
    expect(writes).toEqual([]);
  });

  test("refuses a due day of 0, 29 and 31 with a message that says the range, before sending", async ({ page, scenarioId, request }) => {
    await openPlans(page, scenarioId);
    await openCreate(page, "Nuevo plan de cobro");
    await field(page, "Nombre").fill("Primaria 2027");
    await field(page, "Año lectivo").selectOption({ label: "Año lectivo 2026" });
    await field(page, "Grado").selectOption({ label: "Primero" });

    for (const day of ["0", "29", "31"]) {
      await field(page, "Día de vencimiento").fill(day);
      await save(page).click();
      await expect(field(page, "Día de vencimiento")).toHaveAccessibleDescription(/entre 1 y 28/);
    }

    const writes = (await recordedCalls(request, scenarioId)).filter((call) => call.method === "POST" && call.path === "/api/v1/charges/plans");
    expect(writes).toEqual([]);
  });
});

test.describe("the detail of a plan", () => {
  test("opens from the name of a plan, shows the data and the items with the concepts named, and survives a reload", async ({ page, scenarioId }) => {
    await openPlans(page, scenarioId);
    await page.getByRole("group", { name: "Filtros" }).getByLabel("Estado").selectOption({ label: "Activo" });

    await page.getByRole("link", { name: "Primaria 2026" }).click();

    await expect(page).toHaveURL(new RegExp(`/charges/plans/${SEEDED_PLANS.active}$`));
    await expect(page.getByRole("heading", { level: 2, name: "Primaria 2026" })).toBeVisible();
    await expect.poll(() => itemRows(page, "Conceptos del plan Primaria 2026")).toEqual([
      ["COL — Colegiatura", "10", "1 feb 2026"],
      ["MAT — Matrícula anual", "1", "1 ene 2026"],
    ]);
    const summary = page.getByLabel("Datos del plan");
    await expect(summary).toContainText("Año lectivo 2026");
    await expect(summary).toContainText("Primero");
    await expect(summary).toContainText("Día 5");

    await page.reload();

    await expect(page.getByRole("heading", { level: 2, name: "Primaria 2026" })).toBeVisible();
    await expect.poll(() => itemRows(page, "Conceptos del plan Primaria 2026")).toHaveLength(2);
  });

  test("the acceptance criterion: 'Primaria 2027' gets «Colegiatura» for ten periods from February and «Matrícula» for one, is activated, and its items can no longer change", async ({
    page,
    scenarioId,
  }) => {
    await openPlans(page, scenarioId, "charges-empty");
    await openCreate(page, "Nuevo plan de cobro");
    await field(page, "Nombre").fill("Primaria 2027");
    await field(page, "Año lectivo").selectOption({ label: "Año lectivo 2026" });
    await field(page, "Grado").selectOption({ label: "Primero" });
    await field(page, "Día de vencimiento").fill("5");
    await save(page).click();
    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await page.getByRole("link", { name: "Primaria 2027" }).click();
    await expect(page.getByText("Este plan todavía no tiene conceptos")).toBeVisible();

    await addItem(page, { concept: "Colegiatura", periods: "10", first: "2027-02-01" });
    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Se agregó «Colegiatura» al plan.");
    await addItem(page, { concept: "Matrícula anual", periods: "1", first: "2027-02-01" });
    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);

    await expect.poll(() => itemRows(page, "Conceptos del plan Primaria 2027")).toEqual([
      ["COL — Colegiatura", "10", "1 feb 2027", "Quitar"],
      ["MAT — Matrícula anual", "1", "1 feb 2027", "Quitar"],
    ]);
    await page.getByRole("button", { name: "Activar plan Primaria 2027" }).click();
    await expect(dialog(page)).toContainText("queda congelado");
    await dialog(page).getByRole("button", { name: "Activar plan" }).click();

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Se activó el plan «Primaria 2027».");
    await expect(page.getByText(/Este plan está activo y congelado/)).toBeVisible();
    await expect(page.getByRole("button", { name: ADD_ITEM })).toHaveCount(0);
    await expect(page.getByRole("button", { name: /^Quitar/ })).toHaveCount(0);
    await expect(page.getByRole("button", { name: /Activar plan/ })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Archivar plan Primaria 2027" })).toBeVisible();
  });

  test("offers only the active concepts the plan does not have yet", async ({ page, scenarioId }) => {
    await openPlanDetail(page, scenarioId, SEEDED_PLANS.draftWithItem, "Segundo grado 2026");

    await page.getByRole("button", { name: ADD_ITEM }).click();

    const options = await field(page, "Concepto").locator("option").allTextContents();
    expect(options).toContain("Matrícula anual");
    expect(options).not.toContain("Colegiatura");
    expect(options).not.toContain("Otros ingresos");
  });

  test("removes an item of a draft after a confirmation, and keeps it if the person cancels", async ({ page, scenarioId }) => {
    await openPlanDetail(page, scenarioId, SEEDED_PLANS.draftWithItem, "Segundo grado 2026");
    await expect.poll(() => itemRows(page, "Conceptos del plan Segundo grado 2026")).toHaveLength(1);

    await page.getByRole("button", { name: "Quitar Colegiatura del plan" }).click();
    await expect(dialog(page)).toContainText("Quitar «Colegiatura» del plan");
    await dialog(page).getByRole("button", { name: "Cancelar" }).click();
    await expect(dialog(page)).toBeHidden();
    await expect.poll(() => itemRows(page, "Conceptos del plan Segundo grado 2026")).toHaveLength(1);

    await page.getByRole("button", { name: "Quitar Colegiatura del plan" }).click();
    await dialog(page).getByRole("button", { name: "Quitar concepto" }).click();

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Se quitó «Colegiatura» del plan.");
    await expect(page.getByText("Este plan todavía no tiene conceptos")).toBeVisible();
  });

  test("edits the name, the frequency and the due day of a draft", async ({ page, scenarioId }) => {
    await openPlanDetail(page, scenarioId, SEEDED_PLANS.draftWithItem, "Segundo grado 2026");

    await page.getByRole("button", { name: "Editar plan Segundo grado 2026" }).click();
    await expect(field(page, "Nombre")).toHaveValue("Segundo grado 2026");
    await field(page, "Nombre").fill("Segundo 2026");
    await field(page, "Periodicidad").selectOption({ label: "Bimestral" });
    await field(page, "Día de vencimiento").fill("12");
    await dialog(page).getByRole("button", { name: "Guardar cambios" }).click();

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Se guardaron los cambios del plan «Segundo 2026».");
    await expect(page.getByRole("heading", { level: 2, name: "Segundo 2026" })).toBeVisible();
    await expect(page.getByLabel("Datos del plan")).toContainText("Bimestral");
    await expect(page.getByLabel("Datos del plan")).toContainText("Día 12");
  });

  test("archives an active plan after a confirmation, and an archived plan is for reading only", async ({ page, scenarioId }) => {
    await openPlanDetail(page, scenarioId, SEEDED_PLANS.active, "Primaria 2026");

    await page.getByRole("button", { name: "Archivar plan Primaria 2026" }).click();
    await expect(dialog(page)).toContainText("ya no se puede cambiar ni volver a activar");
    await dialog(page).getByRole("button", { name: "Archivar plan" }).click();

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Se archivó el plan «Primaria 2026».");
    await expect(page.getByText(/Este plan está archivado/)).toBeVisible();
    await expect(page.getByRole("button", { name: /Editar|Activar|Archivar|Agregar|Quitar/ })).toHaveCount(0);
  });

  test("goes back to the plans with the link, and the list shows the new state", async ({ page, scenarioId }) => {
    await openPlanDetail(page, scenarioId, SEEDED_PLANS.active, "Primaria 2026");
    await page.getByRole("button", { name: "Archivar plan Primaria 2026" }).click();
    await dialog(page).getByRole("button", { name: "Archivar plan" }).click();
    await expect(resultNotice(page)).toContainText("Se archivó");

    await page.getByRole("link", { name: "Volver a los planes de cobro" }).click();

    await expect(page).toHaveURL(/\/charges\/plans$/);
    await page.getByRole("group", { name: "Filtros" }).getByLabel("Estado").selectOption({ label: "Archivado" });
    await expect.poll(() => firstColumn(page)).toEqual(["Primaria 2026", "Sección A de primero 2026"]);
  });
});
