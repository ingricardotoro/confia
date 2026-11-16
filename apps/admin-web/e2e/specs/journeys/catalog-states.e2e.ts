import type { Page } from "@playwright/test";
import { bodyRows, dialog, field, firstColumn, openCatalog, openCreate, resultNotice, save, SAVE_TIMEOUT, test } from "../../support/catalog.ts";
import { expect, recordedCalls } from "../../support/scenario.ts";

// What the screen says when the answer is not a plain yes: a business rule of the server, a refusal for lack of
// permission, a list with nothing in it and a list that fails to load. Texts are the Spanish ones a person reads.
// Against the SIMULATED API, which repeats only the rules these journeys need: the real rules are the backend's.
const alert = (page: Page) => dialog(page).getByRole("alert");

async function fillConcept(page: Page, code: string, name: string): Promise<void> {
  await field(page, "Código").fill(code);
  await field(page, "Nombre").fill(name);
  await field(page, "Categoría").selectOption({ label: "Otro" });
}

test.describe("a rule of the server refuses a save", () => {
  test("a repeated code, with other capitals, is refused in Spanish, the dialog stays open, and a new code is saved", async ({ page, scenarioId }) => {
    await openCatalog(page, scenarioId);
    await openCreate(page, "Nuevo concepto");
    await fillConcept(page, "col", "Otra colegiatura");

    await save(page).click();

    await expect(alert(page)).toContainText("Ya existe un registro con ese nombre, código u orden. Escriba un valor distinto.");
    await expect(dialog(page)).toBeVisible();
    await expect(field(page, "Código")).toHaveValue("col");

    await field(page, "Código").fill("COL2");
    await save(page).click();

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Se creó el registro «Otra colegiatura».");
  });

  test("Cancel after a refused save leaves the list as it was", async ({ page, scenarioId }) => {
    await openCatalog(page, scenarioId);
    await expect.poll(() => firstColumn(page)).toHaveLength(25);
    await openCreate(page, "Nuevo concepto");
    await fillConcept(page, "MAT", "Otra matrícula");
    await save(page).click();
    await expect(alert(page)).toBeVisible();

    await dialog(page).getByRole("button", { name: "Cancelar" }).click();

    await expect(dialog(page)).toBeHidden();
    await expect.poll(() => firstColumn(page).then((codes) => codes.slice(0, 3))).toEqual(["COL", "LIB", "MAT"]);
  });

  test("a code that is too long is refused before sending, under its own field", async ({ page, request, scenarioId }) => {
    await openCatalog(page, scenarioId);
    await openCreate(page, "Nuevo concepto");
    await fillConcept(page, "C".repeat(31), "Concepto");

    await save(page).click();

    await expect(dialog(page).getByRole("group", { name: "Hay 1 campo con error." })).toBeVisible();
    await expect(field(page, "Código")).toHaveAccessibleDescription(/Este dato es demasiado largo\./);
    const writes = (await recordedCalls(request, scenarioId)).filter((call) => call.method !== "GET" && call.path.startsWith("/api/v1/catalog"));
    expect(writes).toEqual([]);
  });
});

test.describe("a person without the permission", () => {
  test("is told so plainly when a save is refused with 403, and nothing changes", async ({ page, request, scenarioId }) => {
    await openCatalog(page, scenarioId, "catalog-forbidden");
    await openCreate(page, "Nuevo concepto");
    await fillConcept(page, "NEW", "Nuevo");

    await save(page).click();

    await expect(alert(page)).toContainText("No tiene permiso para realizar esta acción. Si la necesita para su trabajo, solicítela a un administrador de su institución.");
    await dialog(page).getByRole("button", { name: "Cancelar" }).click();
    await expect.poll(() => firstColumn(page)).toHaveLength(25);
    const writes = (await recordedCalls(request, scenarioId)).filter((call) => call.method === "POST" && call.path.startsWith("/api/v1/catalog"));
    expect(writes).toHaveLength(1);
  });

  test("is told so when deactivating from a row is refused, and the row keeps its state", async ({ page, scenarioId }) => {
    await openCatalog(page, scenarioId, "catalog-forbidden");

    await page.getByRole("button", { name: "Desactivar Colegiatura" }).click();

    await expect(resultNotice(page)).toContainText("No tiene permiso para realizar esta acción.");
    await expect(page.getByRole("row", { name: /COL/ })).toContainText("Activo");
    await expect(page.getByRole("button", { name: "Desactivar Colegiatura" })).toBeVisible();
  });
});

test.describe("a list with nothing in it, and a list that does not load", () => {
  test("shows the empty state with its way forward, which opens the form", async ({ page, scenarioId }) => {
    await openCatalog(page, scenarioId, "catalog-empty");

    await expect(page.getByText("Todavía no hay conceptos de pago")).toBeVisible();
    await expect.poll(() => bodyRows(page)).toHaveLength(1);
    await page.getByRole("button", { name: "Crear el primer concepto" }).click();
    await expect(dialog(page).getByRole("heading", { name: "Nuevo concepto de pago" })).toBeVisible();
    await dialog(page).getByRole("button", { name: "Cancelar" }).click();
    await expect(dialog(page)).toBeHidden();
  });

  test("shows the error state with the trace id when the list fails, and Retry loads it", async ({ page, scenarioId }) => {
    // The page retries a read twice by itself (1 s and 2 s), and the simulator fails exactly those three reads.
    test.setTimeout(60_000);
    await openCatalog(page, scenarioId, "catalog-server-error");

    await expect(page.getByText("No se pudo cargar la lista de conceptos de pago")).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeVisible();

    await page.getByRole("button", { name: "Reintentar" }).click();

    await expect(page.getByText("No se pudo cargar la lista de conceptos de pago")).toBeHidden();
    await expect.poll(() => firstColumn(page).then((codes) => codes.length)).toBe(25);
    await expect.poll(() => firstColumn(page).then((codes) => codes[0])).toBe("COL");
  });
});
