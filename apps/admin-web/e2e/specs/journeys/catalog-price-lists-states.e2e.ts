import type { Page } from "@playwright/test";
import {
  applyPreset,
  dialog,
  field,
  firstColumn,
  openCatalog,
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
import { expect, recordedCalls, seedSession } from "../../support/scenario.ts";

// What the screen says when the answer is not a plain yes: a business rule of the server (an overlap of validities, an
// archived list, a record that changed under the editor), a refusal for lack of permission, a list or a detail with
// nothing in it, and one that fails to load. Texts are the Spanish ones a person reads. Against the SIMULATED API, which
// repeats only the rules these journeys need: the real rules are the backend's.
const NEW_PRICE = "Nuevo precio / nueva vigencia";
const SAVE_PRICE = "Guardar precio";
const alert = (page: Page) => dialog(page).getByRole("alert");

async function fillPrice(page: Page, values: { concept: string; amount: string; from: string; to?: string }): Promise<void> {
  await field(page, "Concepto").selectOption({ label: values.concept });
  await field(page, "Importe").fill(values.amount);
  await field(page, "Vigente desde").fill(values.from);
  if (values.to !== undefined) await field(page, "Vigente hasta").fill(values.to);
}

test.describe("a rule of the server refuses a price", () => {
  test("a validity inside the open one is refused in Spanish with the form as it was, and a validity that does not overlap is saved", async ({ page, scenarioId }) => {
    await openPriceListDetail(page, scenarioId, SEEDED_LISTS.institution2026, "2026 — Institución");
    await page.getByRole("button", { name: NEW_PRICE }).click();
    await fillPrice(page, { concept: "Colegiatura", amount: "1700.00", from: "2026-08-01", to: "2026-08-31" });

    await dialog(page).getByRole("button", { name: SAVE_PRICE }).click();

    await expect(alert(page)).toContainText("Ese concepto ya tiene un precio vigente en parte de esas fechas.");
    await expect(dialog(page)).toBeVisible();
    await expect(field(page, "Importe")).toHaveValue("1700.00");
    await expect(field(page, "Vigente desde")).toHaveValue("2026-08-01");
    await expect.poll(() => validityRows(page, "Vigencias de Colegiatura")).toHaveLength(2);

    // A new open validity that starts after the open one replaces it: the server closes the earlier one the day before.
    await field(page, "Vigente desde").fill("2026-09-01");
    await field(page, "Vigente hasta").fill("");
    await dialog(page).getByRole("button", { name: SAVE_PRICE }).click();

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect.poll(() => validityRows(page, "Vigencias de Colegiatura")).toHaveLength(3);
    const rows = await validityRows(page, "Vigencias de Colegiatura");
    expect(rows[1]).toContain("31 ago 2026");
    expect(rows[1]?.at(-1)).toBe("Cerrada");
    expect(rows[0]?.at(-1)).toBe("Abierta");
  });

  test("an open validity that starts on or before the open one is an overlap too", async ({ page, scenarioId }) => {
    await openPriceListDetail(page, scenarioId, SEEDED_LISTS.institution2026, "2026 — Institución");
    await page.getByRole("button", { name: NEW_PRICE }).click();
    await fillPrice(page, { concept: "Colegiatura", amount: "1800.00", from: "2026-07-01" });

    await dialog(page).getByRole("button", { name: SAVE_PRICE }).click();

    await expect(alert(page)).toContainText("ya tiene un precio vigente");
  });

  test("an amount of zero, a negative one, one with too many decimals and an end before the start are refused before sending", async ({ page, request, scenarioId }) => {
    await openPriceListDetail(page, scenarioId, SEEDED_LISTS.institution2026, "2026 — Institución");
    await page.getByRole("button", { name: NEW_PRICE }).click();
    await fillPrice(page, { concept: "Matrícula anual", amount: "0.00", from: "2030-01-01", to: "2029-12-31" });

    await dialog(page).getByRole("button", { name: SAVE_PRICE }).click();

    await expect(dialog(page).getByRole("group", { name: "Hay 2 campos con errores." })).toBeVisible();
    await expect(field(page, "Importe")).toHaveAccessibleDescription(/El importe debe ser mayor que cero\./);
    await expect(field(page, "Vigente hasta")).toHaveAccessibleDescription(/La fecha de fin no puede ser anterior a la de inicio\./);
    await field(page, "Importe").fill("-5");
    await dialog(page).getByRole("button", { name: SAVE_PRICE }).click();
    await expect(field(page, "Importe")).toHaveAccessibleDescription(/El importe debe ser mayor que cero\./);
    await field(page, "Importe").fill("12.34567");
    await dialog(page).getByRole("button", { name: SAVE_PRICE }).click();
    await expect(field(page, "Importe")).toHaveAccessibleDescription(/hasta cuatro decimales/);
    const writes = (await recordedCalls(request, scenarioId)).filter((call) => call.method === "POST" && call.path.endsWith("/items"));
    expect(writes).toEqual([]);
  });

  test("a list that is archived takes no price: the action is off, and the list says why", async ({ page, scenarioId }) => {
    await openPriceListDetail(page, scenarioId, SEEDED_LISTS.archived2025, "2025 — Institución");

    await expect(page.getByText("Esta lista está archivada y no admite precios nuevos. Reactívela para agregar precios.")).toBeVisible();
    await expect(page.getByRole("button", { name: NEW_PRICE })).toBeDisabled();
    await expect(page.getByRole("button", { name: "Reactivar lista 2025 — Institución" })).toBeEnabled();
  });
});

test.describe("a record that changed since it was opened", () => {
  test("a concept edit with a stale version is refused, the list is read again and the second try is saved", async ({ page, scenarioId }) => {
    await openCatalog(page, scenarioId, "catalog-stale-version");
    await page.getByRole("button", { name: "Editar Colegiatura" }).first().click();
    await field(page, "Nombre").fill("Colegiatura mensual");

    await save(page).click();

    await expect(alert(page)).toContainText("El registro cambió desde que lo abrió y sus cambios no se guardaron.");
    await expect(dialog(page)).toBeVisible();
    await dialog(page).getByRole("button", { name: "Cancelar" }).click();
    await expect(dialog(page)).toBeHidden();
    await page.getByRole("button", { name: "Editar Colegiatura" }).first().click();
    await field(page, "Nombre").fill("Colegiatura mensual");
    await save(page).click();

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Se guardaron los cambios de «Colegiatura mensual».");
  });

  test("archiving a list with a stale version is refused in the page, the detail is read again and the second try archives it", async ({ page, scenarioId }) => {
    await openPriceListDetail(page, scenarioId, SEEDED_LISTS.institution2026, "2026 — Institución", "catalog-stale-version");

    await page.getByRole("button", { name: "Archivar lista 2026 — Institución" }).click();

    await expect(resultNotice(page)).toContainText("El registro cambió desde que lo abrió y sus cambios no se guardaron.");
    await expect(page.getByRole("button", { name: NEW_PRICE })).toBeEnabled();
    await page.getByRole("button", { name: "Archivar lista 2026 — Institución" }).click();

    await expect(resultNotice(page)).toContainText("Se archivó la lista «2026 — Institución».");
    await expect(page.getByRole("button", { name: NEW_PRICE })).toBeDisabled();
  });
});

test.describe("a person without the permission", () => {
  test("is told so plainly when a new list is refused with 403, and nothing changes", async ({ page, request, scenarioId }) => {
    await openPriceLists(page, scenarioId, "catalog-forbidden");
    await openCreate(page, "Nueva lista de precios");
    await field(page, "Año lectivo").selectOption({ label: "Año lectivo 2026" });
    await field(page, "Nombre").fill("2027");

    await save(page).click();

    await expect(alert(page)).toContainText("No tiene permiso para realizar esta acción.");
    await dialog(page).getByRole("button", { name: "Cancelar" }).click();
    await expect.poll(() => firstColumn(page)).toHaveLength(25);
    const writes = (await recordedCalls(request, scenarioId)).filter((call) => call.method === "POST" && call.path.startsWith("/api/v1/catalog/price-lists"));
    expect(writes).toHaveLength(1);
  });

  test("is told so when a price or the archive is refused, and the list keeps its state", async ({ page, scenarioId }) => {
    await openPriceListDetail(page, scenarioId, SEEDED_LISTS.institution2026, "2026 — Institución", "catalog-forbidden");
    await page.getByRole("button", { name: NEW_PRICE }).click();
    await fillPrice(page, { concept: "Matrícula anual", amount: "900.00", from: "2027-01-01" });
    await dialog(page).getByRole("button", { name: SAVE_PRICE }).click();
    await expect(alert(page)).toContainText("No tiene permiso para realizar esta acción.");
    await dialog(page).getByRole("button", { name: "Cancelar" }).click();

    await page.getByRole("button", { name: "Archivar lista 2026 — Institución" }).click();

    await expect(resultNotice(page)).toContainText("No tiene permiso para realizar esta acción.");
    await expect(page.getByRole("button", { name: NEW_PRICE })).toBeEnabled();
  });
});

test.describe("lists with nothing in them, and lists that do not load", () => {
  test("shows the empty state of the lists with its way forward, which opens the form", async ({ page, scenarioId }) => {
    await openPriceLists(page, scenarioId, "catalog-price-lists-empty");

    await expect(page.getByText("Todavía no hay listas de precios")).toBeVisible();
    await page.getByRole("button", { name: "Crear la primera lista" }).click();
    await expect(dialog(page).getByRole("heading", { name: "Nueva lista de precios" })).toBeVisible();
    await dialog(page).getByRole("button", { name: "Cancelar" }).click();
    await expect(dialog(page)).toBeHidden();
  });

  test("shows the empty state of a list with no prices, with its way forward, which opens the form of a price", async ({ page, scenarioId }) => {
    await openPriceListDetail(page, scenarioId, SEEDED_LISTS.first2026, "2026 — Primero");

    await expect(page.getByText("Esta lista todavía no tiene precios")).toBeVisible();
    await page.getByRole("button", { name: "Agregar el primer precio" }).click();

    await expect(dialog(page).getByRole("heading", { name: NEW_PRICE })).toBeVisible();
  });

  test("shows the error state of the lists with the trace id when they fail, and Retry loads them", async ({ page, scenarioId }) => {
    // The page retries a read twice by itself (1 s and 2 s), and the simulator fails exactly those three reads.
    test.setTimeout(60_000);
    await openPriceLists(page, scenarioId, "catalog-server-error");

    await expect(page.getByText("No se pudieron cargar las listas de precios")).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeVisible();

    await page.getByRole("button", { name: "Reintentar" }).click();

    await expect(page.getByText("No se pudieron cargar las listas de precios")).toBeHidden();
    await expect.poll(() => firstColumn(page).then((names) => names.length)).toBe(25);
  });

  test("shows the error state of a detail that fails, and Retry loads it", async ({ page, scenarioId }) => {
    test.setTimeout(60_000);
    await applyPresetThenOpen(page, scenarioId);

    await expect(page.getByText("No se pudo cargar la lista de precios")).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeVisible();

    await page.getByRole("button", { name: "Reintentar" }).click();

    await expect(page.getByRole("heading", { level: 2, name: "2026 — Institución" })).toBeVisible();
    // The names of the concepts come from their own list, which the same preset fails first: the page says so and offers its own retry.
    await expect(page.getByText(/No se pudieron cargar los nombres de los conceptos/)).toBeVisible({ timeout: 20_000 });
    await expect(page.getByRole("heading", { level: 4, name: "No disponible" }).first()).toBeVisible();

    await page.getByRole("button", { name: "Reintentar" }).click();

    await expect(page.getByRole("heading", { level: 4, name: "COL — Colegiatura" })).toBeVisible();
    await expect(page.getByText(/No se pudieron cargar los nombres de los conceptos/)).toBeHidden();
  });

  test("tells a list that does not exist as not found, with a way back", async ({ page, scenarioId }) => {
    await openCatalog(page, scenarioId);

    await page.goto("/catalog/price-lists/00000000-0000-4000-8000-000000000001");

    await expect(page.getByText("No se pudo cargar la lista de precios")).toBeVisible({ timeout: 20_000 });
    await expect(page.getByRole("link", { name: "Volver a las listas de precios" })).toBeVisible();
  });
});

/** The detail of a seeded list with a preset applied, without waiting for its heading: the preset makes the page fail first. */
async function applyPresetThenOpen(page: Page, scenarioId: string): Promise<void> {
  await applyPreset(page, scenarioId, "catalog-server-error");
  await seedSession(page);
  await page.goto(`/catalog/price-lists/${SEEDED_LISTS.institution2026}`);
}
