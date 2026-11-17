import type { Page } from "@playwright/test";
import {
  bodyRows,
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
import { expect, recordedCalls } from "../../support/scenario.ts";

// The tax rates through their screen (catalog-tax-rates cut): open the tab of the catalog, create a rate with no
// percentage, open its detail, register a validity, replace it with a new one, deactivate the rate, and assign it to a
// taxable concept. Real Chromium, the production build and the SIMULATED API: a simulation of the published shapes, not a
// test of the real stack or of its rules. The percentages are fictitious: no rate is written in the code or seeded.
const NEW_PERIOD = "Nueva vigencia";
const SAVE_PERIOD = "Guardar vigencia";
const checkbox = (page: Page, name: string) => dialog(page).getByRole("checkbox", { name });

async function addPeriod(page: Page, values: { rate: string; from: string; to?: string }): Promise<void> {
  await page.getByRole("button", { name: NEW_PERIOD }).click();
  await expect(dialog(page)).toBeVisible();
  await field(page, "Porcentaje").fill(values.rate);
  await field(page, "Vigente desde").fill(values.from);
  if (values.to !== undefined) await field(page, "Vigente hasta").fill(values.to);
  await dialog(page).getByRole("button", { name: SAVE_PERIOD }).click();
}

test.describe("the tax rates, creating and listing", () => {
  test("opens 'Tasas de impuesto' from the tab of the catalog, and the tab is the current one", async ({ page, scenarioId }) => {
    await openTaxRates(page, scenarioId);

    await expect(page.getByRole("link", { name: "Tasas de impuesto" })).toHaveAttribute("aria-current", "page");
    await expect(page.getByRole("link", { name: "Conceptos de pago" })).not.toHaveAttribute("aria-current");
    await expect(page.getByRole("table", { name: "Tasas de impuesto de la institución" })).toBeVisible();
    await expect.poll(() => firstColumn(page).then((codes) => codes.slice(0, 3))).toEqual(["IMP-A", "IMP-B", "IMP-C"]);
    await expect(page.getByRole("row", { name: /IMP-C/ })).toContainText("Inactivo");
    await expect(page.getByRole("row", { name: /IMP-A/ })).toContainText("Impuesto de prueba A");
  });

  test("goes from the rates back to the payment concepts with the other tab", async ({ page, scenarioId }) => {
    await openTaxRates(page, scenarioId);

    await page.getByRole("navigation", { name: "Vistas del catálogo" }).getByRole("link", { name: "Conceptos de pago" }).click();

    await expect(page).toHaveURL(/\/catalog$/);
    await expect(page.getByRole("heading", { level: 2, name: "Conceptos de pago" })).toBeVisible();
  });

  test("creates a rate with a code and a name only, from an empty list, and sees it listed with no percentage", async ({ page, scenarioId }) => {
    await openTaxRates(page, scenarioId, "catalog-tax-rates-empty");
    await expect(page.getByText("Todavía no hay tasas de impuesto")).toBeVisible();

    await openCreate(page, "Nueva tasa");
    await expect(dialog(page).getByLabel(/Porcentaje/)).toHaveCount(0);
    await field(page, "Código").fill("ISV");
    await field(page, "Nombre").fill("Impuesto sobre ventas");
    await save(page).click();

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Se creó el registro «Impuesto sobre ventas».");
    await expect.poll(() => bodyRows(page)).toEqual([["ISV", "Impuesto sobre ventas", "Activo", "EditarDesactivar"]]);
    await page.getByRole("link", { name: "ISV" }).click();
    await expect(page.getByText("Esta tasa todavía no tiene vigencias")).toBeVisible();
  });

  test("shows the field errors of an empty form and sends nothing to the API", async ({ page, request, scenarioId }) => {
    await openTaxRates(page, scenarioId);
    await openCreate(page, "Nueva tasa");

    await save(page).click();

    await expect(dialog(page).getByRole("group", { name: "Hay 2 campos con errores." })).toBeVisible();
    await expect(field(page, "Código")).toHaveAccessibleDescription(/Este dato es obligatorio\./);
    await expect(field(page, "Nombre")).toHaveAccessibleDescription(/Este dato es obligatorio\./);
    const writes = (await recordedCalls(request, scenarioId)).filter((call) => call.method !== "GET" && call.path.startsWith("/api/v1/catalog"));
    expect(writes).toEqual([]);
  });

  test("edits the name of a rate with its code fixed, and the list shows the change", async ({ page, scenarioId }) => {
    await openTaxRates(page, scenarioId);

    await page.getByRole("button", { name: "Editar Impuesto de prueba B" }).click();
    await expect(field(page, "Código")).toHaveValue("IMP-B");
    await expect(field(page, "Código")).toHaveAttribute("readonly", "");
    await field(page, "Nombre").fill("Impuesto de prueba B renombrado");
    await save(page).click();

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Se guardaron los cambios de «Impuesto de prueba B renombrado».");
    await expect(page.getByRole("row", { name: /IMP-B/ })).toContainText("Impuesto de prueba B renombrado");
  });

  test("deactivates and activates a rate from its row", async ({ page, scenarioId }) => {
    await openTaxRates(page, scenarioId);

    await page.getByRole("button", { name: "Desactivar Impuesto de prueba A" }).click();
    await expect(resultNotice(page)).toContainText("Se desactivó «Impuesto de prueba A».");
    await expect(page.getByRole("row", { name: /IMP-A/ })).toContainText("Inactivo");

    await page.getByRole("button", { name: "Activar Impuesto de prueba A" }).click();
    await expect(resultNotice(page)).toContainText("Se activó «Impuesto de prueba A».");
    await expect(page.getByRole("row", { name: /IMP-A/ })).toContainText("Activo");
  });
});

test.describe("the detail of a tax rate and its history of validities", () => {
  test("opens from the code of a rate, shows every validity, closed and open, with the percentage at four decimals, and survives a reload", async ({
    page,
    scenarioId,
  }) => {
    await openTaxRates(page, scenarioId);

    await page.getByRole("link", { name: "IMP-A" }).click();

    await expect(page).toHaveURL(new RegExp(`/catalog/tax-rates/${SEEDED_RATES.a}$`));
    await expect(page.getByRole("heading", { level: 2, name: "Impuesto de prueba A" })).toBeVisible();
    await expect(page.getByRole("link", { name: "Tasas de impuesto", exact: true })).toHaveAttribute("aria-current", "page");
    await expect.poll(() => periodRows(page, "Vigencias de Impuesto de prueba A")).toHaveLength(2);
    const [newest, oldest] = await periodRows(page, "Vigencias de Impuesto de prueba A");
    expect(newest?.join(" ")).toMatch(/3\.2100 %/);
    expect(newest?.at(-1)).toBe("Abierta");
    expect(newest).toContain("Sin fecha de fin");
    expect(oldest?.join(" ")).toMatch(/1\.2300 %/);
    expect(oldest?.at(-1)).toBe("Cerrada");
    expect(oldest).toContain("30 jun 2026");

    await page.reload();

    await expect(page.getByRole("heading", { level: 2, name: "Impuesto de prueba A" })).toBeVisible();
    await expect.poll(() => periodRows(page, "Vigencias de Impuesto de prueba A")).toHaveLength(2);
  });

  test("registers a percentage from 1 January and replaces it on 1 July: the first validity closes on 30 June and both are in the history", async ({
    page,
    scenarioId,
  }) => {
    await openTaxRates(page, scenarioId, "catalog-tax-rates-empty");
    await openCreate(page, "Nueva tasa");
    await field(page, "Código").fill("ISV");
    await field(page, "Nombre").fill("Impuesto sobre ventas");
    await save(page).click();
    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await page.getByRole("link", { name: "ISV" }).click();
    await expect(page.getByText("Esta tasa todavía no tiene vigencias")).toBeVisible();

    await addPeriod(page, { rate: "2.5", from: "2027-01-01" });

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Se registró la vigencia de «Impuesto sobre ventas» desde el 1 ene 2027.");
    await expect.poll(() => periodRows(page, "Vigencias de Impuesto sobre ventas")).toHaveLength(1);
    const [first] = await periodRows(page, "Vigencias de Impuesto sobre ventas");
    // The percentage comes back as the server holds it, at four decimals: the browser did not format it from a number.
    expect(first?.join(" ")).toMatch(/2\.5000 %/);

    await addPeriod(page, { rate: "3.75", from: "2027-07-01" });

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect.poll(() => periodRows(page, "Vigencias de Impuesto sobre ventas")).toHaveLength(2);
    const [newest, oldest] = await periodRows(page, "Vigencias de Impuesto sobre ventas");
    expect(newest?.join(" ")).toMatch(/3\.7500 %/);
    expect(newest).toContain("1 jul 2027");
    expect(newest?.at(-1)).toBe("Abierta");
    expect(oldest?.join(" ")).toMatch(/2\.5000 %/);
    expect(oldest).toContain("1 ene 2027");
    expect(oldest).toContain("30 jun 2027");
    expect(oldest?.at(-1)).toBe("Cerrada");
  });

  test("registers a validity with a closing date, which stays closed", async ({ page, scenarioId }) => {
    await openTaxRateDetail(page, scenarioId, SEEDED_RATES.b, "Impuesto de prueba B");

    await addPeriod(page, { rate: "100", from: "2020-02-01", to: "2020-02-29" });

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect.poll(() => periodRows(page, "Vigencias de Impuesto de prueba B")).toHaveLength(2);
    const [, closed] = await periodRows(page, "Vigencias de Impuesto de prueba B");
    expect(closed?.join(" ")).toMatch(/100\.0000 %/);
    expect(closed).toContain("29 feb 2020");
    expect(closed?.at(-1)).toBe("Cerrada");
  });

  test("deactivates a rate, which then takes no new validity, and activates it again", async ({ page, scenarioId }) => {
    await openTaxRateDetail(page, scenarioId, SEEDED_RATES.a, "Impuesto de prueba A");

    await page.getByRole("button", { name: "Desactivar tasa Impuesto de prueba A" }).click();

    await expect(resultNotice(page)).toContainText("Se desactivó la tasa «Impuesto de prueba A».");
    await expect(page.getByText(/está inactiva y no admite vigencias nuevas/)).toBeVisible();
    await expect(page.getByRole("button", { name: NEW_PERIOD })).toBeDisabled();

    await page.getByRole("button", { name: "Activar tasa Impuesto de prueba A" }).click();

    await expect(resultNotice(page)).toContainText("Se activó la tasa «Impuesto de prueba A».");
    await expect(page.getByRole("button", { name: NEW_PERIOD })).toBeEnabled();
  });

  test("goes back to the rates with the link, and the list shows the new state", async ({ page, scenarioId }) => {
    await openTaxRateDetail(page, scenarioId, SEEDED_RATES.a, "Impuesto de prueba A");
    await page.getByRole("button", { name: "Desactivar tasa Impuesto de prueba A" }).click();
    await expect(resultNotice(page)).toContainText("Se desactivó");

    await page.getByRole("link", { name: "Volver a las tasas de impuesto" }).click();

    await expect(page).toHaveURL(/\/catalog\/tax-rates$/);
    await expect(page.getByRole("row", { name: /IMP-A/ })).toContainText("Inactivo");
  });
});

test.describe("the tax rate of a payment concept", () => {
  test("a taxable concept asks for an active rate, which the list then shows, and a concept that is not taxable takes none", async ({ page, scenarioId }) => {
    await openCatalog(page, scenarioId, "catalog-empty");
    await openCreate(page, "Nuevo concepto");
    await field(page, "Código").fill("COL");
    await field(page, "Nombre").fill("Colegiatura");
    await field(page, "Categoría").selectOption({ label: "Colegiatura" });
    await expect(dialog(page).getByLabel(/^Tasa de impuesto/)).toHaveCount(0);

    await checkbox(page, "Gravado").check();
    await expect(field(page, "Tasa de impuesto")).toBeVisible();
    await save(page).click();
    await expect(dialog(page).getByRole("group", { name: "Hay 1 campo con error." })).toBeVisible();
    await expect(field(page, "Tasa de impuesto")).toHaveAccessibleDescription(/Este dato es obligatorio\./);
    // Only the active rates are offered: the inactive one of the seed is not among them.
    const options = await field(page, "Tasa de impuesto").locator("option").allTextContents();
    expect(options).toContain("IMP-A — Impuesto de prueba A");
    expect(options).not.toContain("IMP-C — Impuesto de prueba C");
    await field(page, "Tasa de impuesto").selectOption({ label: "IMP-B — Impuesto de prueba B" });
    await save(page).click();

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(page.getByRole("row", { name: /COL/ })).toContainText("IMP-B — Impuesto de prueba B");

    await openCreate(page, "Nuevo concepto");
    await field(page, "Código").fill("MAT");
    await field(page, "Nombre").fill("Matrícula anual");
    await field(page, "Categoría").selectOption({ label: "Matrícula" });
    await checkbox(page, "Gravado").check();
    await field(page, "Tasa de impuesto").selectOption({ label: "IMP-A — Impuesto de prueba A" });
    await checkbox(page, "Gravado").uncheck();
    await expect(dialog(page).getByLabel(/^Tasa de impuesto/)).toHaveCount(0);
    await save(page).click();

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(page.getByRole("row", { name: /MAT/ })).toContainText("No aplica");
  });

  test("the list of concepts names the rate of a taxable concept, and a concept that is not taxable says it does not apply", async ({ page, scenarioId }) => {
    await openCatalog(page, scenarioId);

    await expect(page.getByRole("row", { name: /Colegiatura/ })).toContainText("IMP-A — Impuesto de prueba A");
    await expect(page.getByRole("row", { name: /Transporte escolar/ })).toContainText("IMP-B — Impuesto de prueba B");
    await expect(page.getByRole("row", { name: /Matrícula anual/ })).toContainText("No aplica");
  });

  test("editing a taxable concept keeps its rate, and changing the rate shows in the list", async ({ page, scenarioId }) => {
    await openCatalog(page, scenarioId);

    await page.getByRole("button", { name: "Editar Colegiatura" }).first().click();
    await expect(field(page, "Tasa de impuesto")).toHaveValue(SEEDED_RATES.a);
    await field(page, "Tasa de impuesto").selectOption({ label: "IMP-B — Impuesto de prueba B" });
    await save(page).click();

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(page.getByRole("row", { name: /COL/ })).toContainText("IMP-B — Impuesto de prueba B");
  });
});
