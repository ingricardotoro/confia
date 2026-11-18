import type { Page } from "@playwright/test";
import {
  applyPreset,
  dialog,
  field,
  firstColumn,
  openCatalog,
  openCreate,
  openTaxRateDetail,
  openTaxRates,
  periodRows,
  resultNotice,
  save,
  SAVE_TIMEOUT,
  SEEDED_RATES,
  test,
} from "../../support/catalog.ts";
import { expect, recordedCalls, seedSession } from "../../support/scenario.ts";

// What the screen says when the answer is not a plain yes: a business rule of the server (an overlap of validities, an
// inactive rate, a repeated code, a record that changed under the editor), a refusal for lack of permission, a list or a
// detail with nothing in it, and one that fails to load. Texts are the Spanish ones a person reads. Against the SIMULATED
// API, which repeats only the rules these journeys need: the real rules are the backend's.
const NEW_PERIOD = "Nueva vigencia";
const SAVE_PERIOD = "Guardar vigencia";
const TABLE = "Vigencias de Impuesto de prueba A";
const alert = (page: Page) => dialog(page).getByRole("alert");

async function fillPeriod(page: Page, values: { rate: string; from: string; to?: string }): Promise<void> {
  await field(page, "Porcentaje").fill(values.rate);
  await field(page, "Vigente desde").fill(values.from);
  if (values.to !== undefined) await field(page, "Vigente hasta").fill(values.to);
}

test.describe("a rule of the server refuses a rate or a validity", () => {
  test("a code that repeats, whatever its capitals, is told under the code field and the form stays as it was", async ({ page, scenarioId }) => {
    await openTaxRates(page, scenarioId);
    await openCreate(page, "Nueva tasa");
    await field(page, "Código").fill("imp-a");
    await field(page, "Nombre").fill("Otra tasa");

    await save(page).click();

    await expect(field(page, "Código")).toHaveAccessibleDescription(/Ya existe una tasa con ese código\./);
    await expect(dialog(page)).toBeVisible();
    await expect(field(page, "Nombre")).toHaveValue("Otra tasa");
  });

  test("a validity inside the open one is refused in Spanish with the form as it was, and one that does not overlap is saved", async ({ page, scenarioId }) => {
    await openTaxRateDetail(page, scenarioId, SEEDED_RATES.a, "Impuesto de prueba A");
    await page.getByRole("button", { name: NEW_PERIOD }).click();
    await fillPeriod(page, { rate: "5", from: "2026-08-01", to: "2026-08-31" });

    await dialog(page).getByRole("button", { name: SAVE_PERIOD }).click();

    await expect(alert(page)).toContainText("Esa tasa ya tiene una vigencia en parte de esas fechas.");
    await expect(dialog(page)).toBeVisible();
    await expect(field(page, "Porcentaje")).toHaveValue("5");
    await expect(field(page, "Vigente desde")).toHaveValue("2026-08-01");
    await expect.poll(() => periodRows(page, TABLE)).toHaveLength(2);

    // A new open validity that starts after the open one replaces it: the server closes the earlier one the day before.
    await field(page, "Vigente desde").fill("2026-09-01");
    await field(page, "Vigente hasta").fill("");
    await dialog(page).getByRole("button", { name: SAVE_PERIOD }).click();

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect.poll(() => periodRows(page, TABLE)).toHaveLength(3);
    const rows = await periodRows(page, TABLE);
    expect(rows[1]).toContain("31 ago 2026");
    expect(rows[1]?.at(-1)).toBe("Cerrada");
    expect(rows[0]?.at(-1)).toBe("Abierta");
  });

  test("an open validity that starts on or before the open one is an overlap too", async ({ page, scenarioId }) => {
    await openTaxRateDetail(page, scenarioId, SEEDED_RATES.a, "Impuesto de prueba A");
    await page.getByRole("button", { name: NEW_PERIOD }).click();
    await fillPeriod(page, { rate: "5", from: "2026-07-01" });

    await dialog(page).getByRole("button", { name: SAVE_PERIOD }).click();

    await expect(alert(page)).toContainText("ya tiene una vigencia");
  });

  test("a percentage of zero, above 100, with too many decimals or not a number, and a date out of the window, are refused before sending", async ({
    page,
    request,
    scenarioId,
  }) => {
    await openTaxRateDetail(page, scenarioId, SEEDED_RATES.a, "Impuesto de prueba A");
    await page.getByRole("button", { name: NEW_PERIOD }).click();
    await fillPeriod(page, { rate: "0", from: "2101-01-01", to: "2029-12-31" });

    await dialog(page).getByRole("button", { name: SAVE_PERIOD }).click();

    await expect(dialog(page).getByRole("group", { name: "Hay 2 campos con errores." })).toBeVisible();
    await expect(field(page, "Porcentaje")).toHaveAccessibleDescription(/El porcentaje debe ser mayor que cero y no pasar de 100\./);
    await expect(field(page, "Vigente desde")).toHaveAccessibleDescription(/La fecha debe estar entre el 1 de enero de 2000 y el 31 de diciembre de 2100\./);
    for (const [rate, message] of [
      ["100.0001", /no pasar de 100/],
      ["12.34567", /hasta cuatro decimales/],
      ["doce", /hasta cuatro decimales/],
    ] as const) {
      await field(page, "Porcentaje").fill(rate);
      await dialog(page).getByRole("button", { name: SAVE_PERIOD }).click();
      await expect(field(page, "Porcentaje")).toHaveAccessibleDescription(message);
    }
    await field(page, "Vigente desde").fill("2030-01-01");
    await field(page, "Porcentaje").fill("5");
    await dialog(page).getByRole("button", { name: SAVE_PERIOD }).click();
    await expect(field(page, "Vigente hasta")).toHaveAccessibleDescription(/La fecha de fin no puede ser anterior a la de inicio\./);
    const writes = (await recordedCalls(request, scenarioId)).filter((call) => call.method === "POST" && call.path.endsWith("/periods"));
    expect(writes).toEqual([]);
  });

  test("an inactive rate takes no validity: the action is off, and the page says why and offers to activate it", async ({ page, scenarioId }) => {
    await openTaxRateDetail(page, scenarioId, SEEDED_RATES.inactive, "Impuesto de prueba C");

    await expect(page.getByText("Esta tasa está inactiva y no admite vigencias nuevas. Actívela para agregar una.")).toBeVisible();
    await expect(page.getByRole("button", { name: NEW_PERIOD })).toBeDisabled();
    await expect(page.getByText("Esta tasa todavía no tiene vigencias")).toBeVisible();

    await page.getByRole("button", { name: "Activar tasa", exact: true }).click();

    await expect(resultNotice(page)).toContainText("Se activó la tasa «Impuesto de prueba C».");
    await expect(page.getByRole("button", { name: NEW_PERIOD })).toBeEnabled();
  });
});

test.describe("a record that changed since it was opened", () => {
  test("a rate edit with a stale version is refused, the list is read again and the second try is saved", async ({ page, scenarioId }) => {
    await openTaxRates(page, scenarioId, "catalog-stale-version");
    await page.getByRole("button", { name: "Editar Impuesto de prueba A" }).click();
    await field(page, "Nombre").fill("Impuesto de prueba A2");

    await save(page).click();

    await expect(alert(page)).toContainText("El registro cambió desde que lo abrió y sus cambios no se guardaron.");
    await expect(dialog(page)).toBeVisible();
    await dialog(page).getByRole("button", { name: "Cancelar" }).click();
    await expect(dialog(page)).toBeHidden();
    await page.getByRole("button", { name: "Editar Impuesto de prueba A" }).click();
    await field(page, "Nombre").fill("Impuesto de prueba A2");
    await save(page).click();

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Se guardaron los cambios de «Impuesto de prueba A2».");
  });

  test("deactivating a rate with a stale version is refused in the page, the detail is read again and the second try deactivates it", async ({
    page,
    scenarioId,
  }) => {
    await openTaxRateDetail(page, scenarioId, SEEDED_RATES.a, "Impuesto de prueba A", "catalog-stale-version");

    await page.getByRole("button", { name: "Desactivar tasa Impuesto de prueba A" }).click();

    await expect(resultNotice(page)).toContainText("El registro cambió desde que lo abrió y sus cambios no se guardaron.");
    await expect(page.getByRole("button", { name: NEW_PERIOD })).toBeEnabled();
    await page.getByRole("button", { name: "Desactivar tasa Impuesto de prueba A" }).click();

    await expect(resultNotice(page)).toContainText("Se desactivó la tasa «Impuesto de prueba A».");
    await expect(page.getByRole("button", { name: NEW_PERIOD })).toBeDisabled();
  });
});

test.describe("a person without the permission", () => {
  test("is told so plainly when a new rate is refused with 403, and nothing changes", async ({ page, request, scenarioId }) => {
    await openTaxRates(page, scenarioId, "catalog-forbidden");
    await openCreate(page, "Nueva tasa");
    await field(page, "Código").fill("ISV");
    await field(page, "Nombre").fill("Impuesto sobre ventas");

    await save(page).click();

    await expect(alert(page)).toContainText("No tiene permiso para realizar esta acción.");
    await dialog(page).getByRole("button", { name: "Cancelar" }).click();
    await expect.poll(() => firstColumn(page)).toHaveLength(25);
    const writes = (await recordedCalls(request, scenarioId)).filter((call) => call.method === "POST" && call.path.startsWith("/api/v1/catalog/tax-rates"));
    expect(writes).toHaveLength(1);
  });

  test("is told so when a validity or a deactivation is refused, and the rate keeps its state", async ({ page, scenarioId }) => {
    await openTaxRateDetail(page, scenarioId, SEEDED_RATES.a, "Impuesto de prueba A", "catalog-forbidden");
    await page.getByRole("button", { name: NEW_PERIOD }).click();
    await fillPeriod(page, { rate: "5", from: "2027-01-01" });
    await dialog(page).getByRole("button", { name: SAVE_PERIOD }).click();
    await expect(alert(page)).toContainText("No tiene permiso para realizar esta acción.");
    await dialog(page).getByRole("button", { name: "Cancelar" }).click();

    await page.getByRole("button", { name: "Desactivar tasa Impuesto de prueba A" }).click();

    await expect(resultNotice(page)).toContainText("No tiene permiso para realizar esta acción.");
    await expect(page.getByRole("button", { name: NEW_PERIOD })).toBeEnabled();
  });
});

test.describe("lists with nothing in them, and lists that do not load", () => {
  test("shows the empty state of the rates with its way forward, which opens the form", async ({ page, scenarioId }) => {
    await openTaxRates(page, scenarioId, "catalog-tax-rates-empty");

    await expect(page.getByText("Todavía no hay tasas de impuesto")).toBeVisible();
    await page.getByRole("button", { name: "Crear la primera tasa" }).click();
    await expect(dialog(page).getByRole("heading", { name: "Nueva tasa de impuesto" })).toBeVisible();
    await dialog(page).getByRole("button", { name: "Cancelar" }).click();
    await expect(dialog(page)).toBeHidden();
  });

  test("a taxable concept says there is no active rate to choose when the institution has none", async ({ page, scenarioId }) => {
    await openCatalog(page, scenarioId, "catalog-tax-rates-empty");
    await openCreate(page, "Nuevo concepto");
    // The fields are filled first: leaving an empty one shows its error and moves the checkbox before it is pressed.
    await field(page, "Código").fill("COL");
    await field(page, "Nombre").fill("Colegiatura");
    await field(page, "Categoría").selectOption({ label: "Colegiatura" });

    await dialog(page).getByRole("checkbox", { name: "Gravado" }).check();

    await expect(dialog(page).getByText(/No hay tasas de impuesto activas/)).toBeVisible();
  });

  test("shows the empty state of a rate with no validity, with its way forward, which opens the form of a validity", async ({ page, scenarioId }) => {
    await openTaxRateDetail(page, scenarioId, SEEDED_RATES.inactive, "Impuesto de prueba C");
    await page.getByRole("button", { name: "Activar tasa", exact: true }).click();
    await expect(resultNotice(page)).toContainText("Se activó");

    await page.getByRole("button", { name: "Registrar la primera vigencia" }).click();

    await expect(dialog(page).getByRole("heading", { name: NEW_PERIOD })).toBeVisible();
  });

  test("shows the error state of the rates with the trace id when they fail, and Retry loads them", async ({ page, scenarioId }) => {
    // The page retries a read twice by itself (1 s and 2 s), and the simulator fails exactly those three reads.
    test.setTimeout(60_000);
    await openTaxRates(page, scenarioId, "catalog-server-error");

    await expect(page.getByText("No se pudo cargar la lista de tasas de impuesto")).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeVisible();

    await page.getByRole("button", { name: "Reintentar" }).click();

    await expect(page.getByText("No se pudo cargar la lista de tasas de impuesto")).toBeHidden();
    await expect.poll(() => firstColumn(page).then((codes) => codes.length)).toBe(25);
  });

  test("shows the error state of a detail that fails, and Retry loads it", async ({ page, scenarioId }) => {
    test.setTimeout(60_000);
    await applyPresetThenOpen(page, scenarioId);

    await expect(page.getByText("No se pudo cargar la tasa de impuesto")).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeVisible();

    await page.getByRole("button", { name: "Reintentar" }).click();

    await expect(page.getByRole("heading", { level: 2, name: "Impuesto de prueba A" })).toBeVisible();
    await expect.poll(() => periodRows(page, TABLE)).toHaveLength(2);
  });

  test("tells a rate that does not exist as not found, with a way back", async ({ page, scenarioId }) => {
    await openCatalog(page, scenarioId);

    await page.goto("/catalog/tax-rates/00000000-0000-4000-8000-000000000001");

    await expect(page.getByText("No se pudo cargar la tasa de impuesto")).toBeVisible({ timeout: 20_000 });
    await expect(page.getByRole("link", { name: "Volver a las tasas de impuesto" })).toBeVisible();
  });
});

/** The detail of a seeded rate with a preset applied, without waiting for its heading: the preset makes the page fail first. */
async function applyPresetThenOpen(page: Page, scenarioId: string): Promise<void> {
  await applyPreset(page, scenarioId, "catalog-server-error");
  await seedSession(page);
  await page.goto(`/catalog/tax-rates/${SEEDED_RATES.a}`);
}
