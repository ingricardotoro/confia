import { expect } from "../../support/scenario.ts";
import { alertOf, dialog, field, openCreate, openGuardians, openStudentsForLinks, save, test } from "../../support/guardians.ts";

// The states of the guardians screens: empty, a load that fails and recovers, a write the role may not make, the form refused
// before sending, and the dialog of the guardians of a student when it cannot be read or changed. Against the SIMULATED API.
test.describe("the states of the guardians registry", () => {
  test("shows the empty state of a registry with no guardians, with the way to create the first", async ({ page, scenarioId }) => {
    await openGuardians(page, scenarioId, "guardians-empty");

    await expect(page.getByText("Todavía no hay encargados")).toBeVisible();
    await page.getByRole("button", { name: "Registrar al primer encargado" }).click();
    await expect(dialog(page)).toBeVisible();
    await expect(dialog(page)).toHaveAccessibleName("Nuevo encargado");
  });

  test("shows the load error with the trace id and a retry that loads the list", async ({ page, scenarioId }) => {
    await openGuardians(page, scenarioId, "guardians-server-error");

    // The page retries a read twice by itself and the simulator fails exactly those three reads.
    await expect(page.getByText("No se pudo cargar la lista de encargados")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole("alert")).toContainText("Identificador para soporte: 01J8Z4K2QF9P0X");
    await page.getByRole("button", { name: "Reintentar" }).click();

    await expect(page.getByRole("cell", { name: "Aguilar", exact: true }).first()).toBeVisible();
  });

  test("refuses an empty form with a summary that moves the focus, and tells a 403 in plain words without changing the screen", async ({ page, scenarioId }) => {
    await openGuardians(page, scenarioId, "guardians-forbidden");
    await openCreate(page, "Nuevo encargado");

    await save(page).click();
    await expect(dialog(page).getByText("Hay 2 campos con errores.")).toBeVisible();
    await expect(dialog(page).getByRole("group", { name: "Hay 2 campos con errores." })).toBeFocused();
    await field(page, "Nombres").fill("Eva");
    await field(page, "Apellidos").fill("Rivas");
    await field(page, "RTN").fill("123");
    await save(page).click();
    await expect(dialog(page).getByText("El RTN debe tener 14 dígitos").first()).toBeVisible();
    await field(page, "RTN").fill("");
    await save(page).click();

    await expect(alertOf(page)).toContainText("No tiene permiso para realizar esta acción.");
    await expect(dialog(page)).toBeVisible();
  });

  test("a 403 on the guardians of a student is told inside the dialog and the dialog stays", async ({ page, scenarioId }) => {
    await openStudentsForLinks(page, scenarioId, "guardians-forbidden");
    await page.getByRole("searchbox", { name: "Buscar" }).fill("Aguilar");
    await page.getByRole("button", { name: "Encargados de Ana Aguilar" }).click();
    await expect(dialog(page).getByText(/Suma guardada actualmente/)).toBeVisible();

    await dialog(page).getByRole("button", { name: "Guardar encargados" }).click();

    await expect(alertOf(page)).toContainText("No tiene permiso para realizar esta acción.");
    await expect(dialog(page)).toBeVisible();
  });

  test("the dialog of the guardians of a student shows its load error with a retry", async ({ page, scenarioId }) => {
    await openStudentsForLinks(page, scenarioId, "guardians-server-error");
    await page.getByRole("searchbox", { name: "Buscar" }).fill("Aguilar");
    await page.getByRole("button", { name: "Encargados de Ana Aguilar" }).click();

    await expect(dialog(page).getByText("No se pudieron cargar los encargados del estudiante")).toBeVisible({ timeout: 15_000 });
    await dialog(page).getByRole("button", { name: "Reintentar" }).click();

    await expect(dialog(page).getByText(/Suma guardada actualmente/)).toBeVisible();
  });
});
