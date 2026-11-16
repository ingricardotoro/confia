import type { Page } from "@playwright/test";
import {
  bodyRows,
  dialog,
  field,
  firstColumn,
  openCreate,
  openPriceListDetail,
  openPriceLists,
  resultNotice,
  save,
  SAVE_TIMEOUT,
  SEEDED_LISTS,
  test,
  validityRows,
} from "../../support/catalog.ts";
import { expect, recordedCalls } from "../../support/scenario.ts";

// The price lists through their screen (catalog-price-lists cut): open the tab of the catalog, create a list, open its
// detail, set a price, replace it with a new validity and archive the list. Real Chromium, the production build and the
// SIMULATED API: a simulation of the published shapes, not a test of the real stack or of its rules.
const NEW_PRICE = "Nuevo precio / nueva vigencia";
const SAVE_PRICE = "Guardar precio";

async function addPrice(page: Page, values: { concept: string; amount: string; from: string; to?: string; currency?: string }): Promise<void> {
  await page.getByRole("button", { name: NEW_PRICE }).click();
  await expect(dialog(page)).toBeVisible();
  await field(page, "Concepto").selectOption({ label: values.concept });
  await field(page, "Importe").fill(values.amount);
  if (values.currency !== undefined) await field(page, "Moneda").selectOption({ label: values.currency });
  await field(page, "Vigente desde").fill(values.from);
  if (values.to !== undefined) await field(page, "Vigente hasta").fill(values.to);
  await dialog(page).getByRole("button", { name: SAVE_PRICE }).click();
}

test.describe("the price lists, creating and listing", () => {
  test("opens 'Listas de precios' from the tab of the catalog, and the tab is the current one", async ({ page, scenarioId }) => {
    await openPriceLists(page, scenarioId);

    await expect(page.getByRole("link", { name: "Listas de precios" })).toHaveAttribute("aria-current", "page");
    await expect(page.getByRole("link", { name: "Conceptos de pago" })).not.toHaveAttribute("aria-current");
    await expect(page.getByRole("table", { name: "Listas de precios de la institución" })).toBeVisible();
    await expect.poll(() => firstColumn(page).then((names) => names.slice(0, 3))).toEqual(["2025 — Institución", "2026 — Institución", "2026 — Primero"]);
    const row = page.getByRole("row", { name: /2026 — Primero/ });
    await expect(row).toContainText("Año lectivo 2026");
    await expect(row).toContainText("Un grado");
    await expect(row).toContainText("Primero");
    await expect(page.getByRole("row", { name: /2025 — Institución/ })).toContainText("Archivada");
  });

  test("goes from the lists back to the payment concepts with the other tab", async ({ page, scenarioId }) => {
    await openPriceLists(page, scenarioId);

    await page.getByRole("navigation", { name: "Vistas del catálogo" }).getByRole("link", { name: "Conceptos de pago" }).click();

    await expect(page).toHaveURL(/\/catalog$/);
    await expect(page.getByRole("heading", { level: 2, name: "Conceptos de pago" })).toBeVisible();
  });

  test("creates '2027 — Institución' for the whole institution from an empty list and sees it listed", async ({ page, scenarioId }) => {
    await openPriceLists(page, scenarioId, "catalog-price-lists-empty");
    await expect(page.getByText("Todavía no hay listas de precios")).toBeVisible();

    await openCreate(page, "Nueva lista de precios");
    await field(page, "Año lectivo").selectOption({ label: "Año lectivo 2026" });
    await expect(field(page, "Ámbito")).toHaveValue("INSTITUTION");
    await field(page, "Nombre").fill("2027 — Institución");
    await save(page).click();

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Se creó el registro «2027 — Institución».");
    await expect.poll(() => bodyRows(page)).toEqual([["2027 — Institución", "Año lectivo 2026", "Toda la institución", "No aplica", "Activa"]]);
  });

  test("creates a list for one grade, asking for the grade, and names it in the list", async ({ page, scenarioId }) => {
    await openPriceLists(page, scenarioId, "catalog-price-lists-empty");
    await openCreate(page, "Nueva lista de precios");
    await expect(dialog(page).getByLabel(/^Grado/)).toHaveCount(0);

    await field(page, "Año lectivo").selectOption({ label: "Año lectivo 2026" });
    await field(page, "Ámbito").selectOption({ label: "Un grado" });
    await field(page, "Grado").selectOption({ label: "Séptimo" });
    await field(page, "Nombre").fill("2027 — Séptimo");
    await save(page).click();

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect.poll(() => bodyRows(page)).toEqual([["2027 — Séptimo", "Año lectivo 2026", "Un grado", "Séptimo", "Activa"]]);
  });

  test("creates a list for one modality, and forgets the grade chosen before the scope changed", async ({ page, scenarioId }) => {
    await openPriceLists(page, scenarioId, "catalog-price-lists-empty");
    await openCreate(page, "Nueva lista de precios");
    await field(page, "Año lectivo").selectOption({ label: "Año lectivo 2026" });
    await field(page, "Ámbito").selectOption({ label: "Un grado" });
    await field(page, "Grado").selectOption({ label: "Séptimo" });

    await field(page, "Ámbito").selectOption({ label: "Una modalidad" });
    await expect(field(page, "Modalidad")).toHaveValue("");
    await field(page, "Modalidad").selectOption({ label: "Secundaria" });
    await field(page, "Nombre").fill("2027 — Secundaria");
    await save(page).click();

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect.poll(() => bodyRows(page)).toEqual([["2027 — Secundaria", "Año lectivo 2026", "Una modalidad", "Secundaria", "Activa"]]);
  });

  test("shows the field errors of an empty form and sends nothing to the API", async ({ page, scenarioId, request }) => {
    await openPriceLists(page, scenarioId);
    await openCreate(page, "Nueva lista de precios");

    await save(page).click();

    await expect(dialog(page).getByRole("group", { name: "Hay 2 campos con errores." })).toBeVisible();
    await expect(field(page, "Año lectivo")).toHaveAccessibleDescription(/Este dato es obligatorio\./);
    await expect(field(page, "Nombre")).toHaveAccessibleDescription(/Este dato es obligatorio\./);
    const writes = (await recordedCalls(request, scenarioId)).filter((call) => call.method !== "GET" && call.path.startsWith("/api/v1/catalog"));
    expect(writes).toEqual([]);
  });
});

test.describe("the detail of a price list", () => {
  test("opens from the name of a list, shows the data and the validities of each concept, closed and open, and survives a reload", async ({ page, scenarioId }) => {
    await openPriceLists(page, scenarioId);

    await page.getByRole("link", { name: "2026 — Institución" }).click();

    await expect(page).toHaveURL(new RegExp(`/catalog/price-lists/${SEEDED_LISTS.institution2026}$`));
    await expect(page.getByRole("heading", { level: 2, name: "2026 — Institución" })).toBeVisible();
    await expect(page.getByRole("link", { name: "Listas de precios", exact: true })).toHaveAttribute("aria-current", "page");
    await expect(page.getByRole("heading", { level: 4, name: "COL — Colegiatura" })).toBeVisible();
    await expect.poll(() => validityRows(page, "Vigencias de Colegiatura")).toHaveLength(2);
    const [newest, oldest] = await validityRows(page, "Vigencias de Colegiatura");
    expect(newest?.join(" ")).toMatch(/1,650\.00/);
    expect(newest?.at(-1)).toBe("Abierta");
    expect(newest).toContain("Sin fecha de fin");
    expect(oldest?.join(" ")).toMatch(/1,500\.00/);
    expect(oldest?.at(-1)).toBe("Cerrada");
    expect(oldest).toContain("30 jun 2026");

    await page.reload();

    await expect(page.getByRole("heading", { level: 2, name: "2026 — Institución" })).toBeVisible();
    await expect(page.getByRole("heading", { level: 4, name: "TRA — Transporte escolar" })).toBeVisible();
  });

  test("shows a currency of its own in each table and the amounts with two decimals as the server sent them", async ({ page, scenarioId }) => {
    await openPriceListDetail(page, scenarioId, SEEDED_LISTS.institution2026, "2026 — Institución");

    const [transport] = await validityRows(page, "Vigencias de Transporte escolar");

    expect(transport?.join(" ")).toMatch(/35\.50/);
    expect(transport).toContain("USD");
    await expect.poll(() => validityRows(page, "Vigencias de Matrícula anual").then((rows) => rows[0]?.includes("HNL"))).toBe(true);
  });

  test("sets 'Colegiatura' to L 1 500,00 from 1 January and replaces it on 1 July: the first validity closes on 30 June and both are in the history", async ({ page, scenarioId }) => {
    await openPriceLists(page, scenarioId, "catalog-price-lists-empty");
    await openCreate(page, "Nueva lista de precios");
    await field(page, "Año lectivo").selectOption({ label: "Año lectivo 2026" });
    await field(page, "Nombre").fill("2027 — Institución");
    await save(page).click();
    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await page.getByRole("link", { name: "2027 — Institución" }).click();
    await expect(page.getByText("Esta lista todavía no tiene precios")).toBeVisible();

    await addPrice(page, { concept: "Colegiatura", amount: "1500.00", from: "2027-01-01" });

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Se registró el precio de «Colegiatura» desde el 1 ene 2027.");
    await expect.poll(() => validityRows(page, "Vigencias de Colegiatura")).toHaveLength(1);

    await addPrice(page, { concept: "Colegiatura", amount: "1650.00", from: "2027-07-01" });

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect.poll(() => validityRows(page, "Vigencias de Colegiatura")).toHaveLength(2);
    const [newest, oldest] = await validityRows(page, "Vigencias de Colegiatura");
    expect(newest?.join(" ")).toMatch(/1,650\.00/);
    expect(newest).toContain("1 jul 2027");
    expect(newest?.at(-1)).toBe("Abierta");
    expect(oldest?.join(" ")).toMatch(/1,500\.00/);
    expect(oldest).toContain("1 ene 2027");
    expect(oldest).toContain("30 jun 2027");
    expect(oldest?.at(-1)).toBe("Cerrada");
  });

  test("sets a price in dollars with a closing date, and the amount comes back as sent, with two decimals", async ({ page, scenarioId }) => {
    await openPriceListDetail(page, scenarioId, SEEDED_LISTS.first2026, "2026 — Primero");
    await expect(page.getByText("Esta lista todavía no tiene precios")).toBeVisible();

    await addPrice(page, { concept: "Libros y útiles", amount: "12.50", currency: "Dólar estadounidense (USD)", from: "2026-02-01", to: "2026-02-28" });

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect.poll(() => validityRows(page, "Vigencias de Libros y útiles")).toHaveLength(1);
    const [row] = await validityRows(page, "Vigencias de Libros y útiles");
    expect(row?.join(" ")).toMatch(/12\.50/);
    expect(row).toContain("USD");
    expect(row).toContain("28 feb 2026");
    expect(row?.at(-1)).toBe("Cerrada");
  });

  test("offers only the active concepts in the form of a price", async ({ page, scenarioId }) => {
    await openPriceListDetail(page, scenarioId, SEEDED_LISTS.institution2026, "2026 — Institución");

    await page.getByRole("button", { name: NEW_PRICE }).click();

    const options = await field(page, "Concepto").locator("option").allTextContents();
    expect(options).toContain("Colegiatura");
    expect(options).not.toContain("Otros ingresos");
  });

  test("archives a list, which then takes no new price, and reactivates it", async ({ page, scenarioId }) => {
    await openPriceListDetail(page, scenarioId, SEEDED_LISTS.institution2026, "2026 — Institución");

    await page.getByRole("button", { name: "Archivar lista 2026 — Institución" }).click();

    await expect(resultNotice(page)).toContainText("Se archivó la lista «2026 — Institución».");
    await expect(page.getByText(/está archivada y no admite precios nuevos/)).toBeVisible();
    await expect(page.getByRole("button", { name: NEW_PRICE })).toBeDisabled();

    await page.getByRole("button", { name: "Reactivar lista 2026 — Institución" }).click();

    await expect(resultNotice(page)).toContainText("Se reactivó la lista «2026 — Institución».");
    await expect(page.getByRole("button", { name: NEW_PRICE })).toBeEnabled();
  });

  test("goes back to the lists with the link, and the list shows the new state", async ({ page, scenarioId }) => {
    await openPriceListDetail(page, scenarioId, SEEDED_LISTS.institution2026, "2026 — Institución");
    await page.getByRole("button", { name: "Archivar lista 2026 — Institución" }).click();
    await expect(resultNotice(page)).toContainText("Se archivó");

    await page.getByRole("link", { name: "Volver a las listas de precios" }).click();

    await expect(page).toHaveURL(/\/catalog\/price-lists$/);
    await expect(page.getByRole("row", { name: /2026 — Institución/ })).toContainText("Archivada");
  });
});
