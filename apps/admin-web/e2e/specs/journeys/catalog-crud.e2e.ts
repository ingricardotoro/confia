import { randomUUID } from "node:crypto";
import {
  applyPreset,
  bodyRows,
  dialog,
  field,
  firstColumn,
  openCatalog,
  openCatalogPage,
  openCreate,
  resultNotice,
  save,
  SAVE_TIMEOUT,
  test,
} from "../../support/catalog.ts";
import { expect, recordedCalls } from "../../support/scenario.ts";

// The payment concepts through their screen (catalog-payment-concepts cut): sign in, open the catalog from the
// navigation, create, edit and deactivate. Real Chromium, the production build, and the SIMULATED API: a simulation of
// the published shapes, not a test of the real stack or of its rules.
const IDENTIFIER = "docente.prueba@example.test";
const checkbox = (page: import("@playwright/test").Page, name: string) => dialog(page).getByRole("checkbox", { name });

test.describe("the payment concepts, creating and editing", () => {
  test("a person signs in, opens 'Catálogo' from the navigation and lands on the payment concepts", async ({ page }) => {
    await page.goto("/iniciar-sesion");
    await page.locator("#sign-in-identifier").fill(IDENTIFIER);
    // Made at run time, so no password is written here.
    await page.locator("#sign-in-password").fill(randomUUID());
    await page.getByRole("button", { name: "Iniciar sesión" }).click();
    await expect(page.getByRole("heading", { level: 1, name: "Sesión iniciada." })).toBeVisible();

    await page.getByRole("navigation", { name: "Navegación principal" }).getByRole("link", { name: "Catálogo" }).click();

    await expect(page).toHaveURL(/\/catalog$/);
    await expect(page.getByRole("heading", { level: 1, name: "Catálogo" })).toBeVisible();
    await expect(page.getByRole("heading", { level: 2, name: "Conceptos de pago" })).toBeVisible();
    await expect(page.getByRole("link", { name: "Catálogo" })).toHaveAttribute("aria-current", "page");
    await expect(page.getByRole("table", { name: "Conceptos de pago de la institución" })).toBeVisible();
    await expect.poll(() => firstColumn(page)).toContain("COL");
  });

  test("creates 'Colegiatura' as recurring and taxable from an empty catalog and sees it listed", async ({ page, scenarioId }) => {
    await openCatalog(page, scenarioId, "catalog-empty");
    await expect(page.getByText("Todavía no hay conceptos de pago")).toBeVisible();

    await openCreate(page, "Nuevo concepto");
    await expect(dialog(page).getByRole("checkbox", { name: "Activo" })).toHaveCount(0);
    await field(page, "Código").fill("COL");
    await field(page, "Nombre").fill("Colegiatura");
    await field(page, "Categoría").selectOption({ label: "Colegiatura" });
    await checkbox(page, "Recurrente").check();
    await checkbox(page, "Gravado").check();
    await save(page).click();

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Se creó el registro «Colegiatura».");
    await expect.poll(() => bodyRows(page)).toEqual([["COL", "Colegiatura", "Colegiatura", "Sí", "Sí", "Activo", "EditarDesactivar"]]);
  });

  test("shows the field errors of an empty form and sends nothing to the API", async ({ page, request, scenarioId }) => {
    await openCatalog(page, scenarioId, "catalog-empty");
    await openCreate(page, "Nuevo concepto");

    await save(page).click();

    await expect(dialog(page).getByRole("group", { name: "Hay 3 campos con errores." })).toBeVisible();
    await expect(field(page, "Código")).toHaveAccessibleDescription(/Este dato es obligatorio\./);
    await expect(field(page, "Categoría")).toHaveAccessibleDescription(/Este dato es obligatorio\./);
    const writes = (await recordedCalls(request, scenarioId)).filter((call) => call.method !== "GET" && call.path.startsWith("/api/v1/catalog"));
    expect(writes).toEqual([]);
  });

  test("edits a concept with its code fixed, and the list shows the change", async ({ page, scenarioId }) => {
    await openCatalog(page, scenarioId);

    await page.getByRole("button", { name: "Editar Transporte escolar" }).click();
    await expect(field(page, "Código")).toHaveValue("TRA");
    await expect(field(page, "Código")).toHaveAttribute("readonly", "");
    await expect(checkbox(page, "Activo")).toBeChecked();
    await field(page, "Nombre").fill("Transporte rutas norte");
    await field(page, "Categoría").selectOption({ label: "Otro" });
    await checkbox(page, "Gravado").uncheck();
    await save(page).click();

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Se guardaron los cambios de «Transporte rutas norte».");
    await expect(page.getByRole("row", { name: /Transporte rutas norte/ })).toContainText("TRA");
    await expect(page.getByRole("row", { name: /Transporte rutas norte/ })).toContainText("Otro");
  });

  test("deactivates a concept from the edit form with the active switch, and reactivates it from its row", async ({ page, scenarioId }) => {
    await openCatalog(page, scenarioId);

    await page.getByRole("button", { name: "Editar Colegiatura" }).first().click();
    await checkbox(page, "Activo").uncheck();
    await save(page).click();

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(page.getByRole("row", { name: /COL/ })).toContainText("Inactivo");
    await expect(page.getByRole("button", { name: "Activar Colegiatura" })).toBeVisible();

    await page.getByRole("button", { name: "Activar Colegiatura" }).click();
    await expect(resultNotice(page)).toContainText("Se activó «Colegiatura».");
    await expect(page.getByRole("row", { name: /COL/ })).toContainText("Activo");
  });

  test("deactivates and activates a concept from its row", async ({ page, scenarioId }) => {
    await openCatalog(page, scenarioId);

    await page.getByRole("button", { name: "Desactivar Libros y útiles" }).click();
    await expect(resultNotice(page)).toContainText("Se desactivó «Libros y útiles».");
    await expect(page.getByRole("row", { name: /Libros y útiles/ })).toContainText("Inactivo");

    await page.getByRole("button", { name: "Activar Libros y útiles" }).click();
    await expect(resultNotice(page)).toContainText("Se activó «Libros y útiles».");
    await expect(page.getByRole("row", { name: /Libros y útiles/ })).toContainText("Activo");
  });

  test("the catalog is reachable again after a reload, with its own address", async ({ page, scenarioId }) => {
    await applyPreset(page, scenarioId, "default");
    await openCatalogPage(page);

    await page.reload();

    await expect(page.getByRole("heading", { level: 2, name: "Conceptos de pago" })).toBeVisible();
    await expect.poll(() => firstColumn(page)).toContain("COL");
  });
});
