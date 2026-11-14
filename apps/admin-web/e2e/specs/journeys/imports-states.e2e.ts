import { expect } from "../../support/scenario.ts";
import { choose, csvOf, dialog, fileField, openImports, recordImportCalls, simulateButton, summaryHeading, test } from "../../support/imports.ts";

// The states of the import screen against the SIMULATED API: an empty history with the way to the field, a history that fails and
// recovers, the checks in the browser, a file the server cannot read, and a role that may read but not import (a 403 told in plain
// words that changes nothing on the screen).
test.describe("the states of the import screen", () => {
  test("shows the empty history with the way to the file field", async ({ page, scenarioId }) => {
    await openImports(page, scenarioId, "imports-empty");

    await expect(page.getByText("Todavía no hay importaciones")).toBeVisible();
    await page.getByRole("button", { name: "Elegir un archivo" }).click();
    await expect(fileField(page)).toBeFocused();
  });

  test("shows the history load error with the trace id and a retry that loads it", async ({ page, scenarioId }) => {
    await openImports(page, scenarioId, "imports-server-error");

    // The page retries a read twice by itself and the simulator fails exactly those three reads.
    await expect(page.getByText("No se pudo cargar el historial de importaciones")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole("alert")).toContainText("Identificador para soporte: 01J8Z4K2QF9P0X");
    await page.getByRole("button", { name: "Reintentar" }).click();

    await expect(page.getByRole("cell", { name: "Aplicada" })).toBeVisible();
  });

  test("asks for a file, and refuses a file that is not a CSV before sending anything", async ({ page, scenarioId }) => {
    const calls = recordImportCalls(page);
    await openImports(page, scenarioId);

    await simulateButton(page).click();
    await expect(fileField(page)).toBeFocused();
    await expect(fileField(page)).toHaveAccessibleDescription(/Elija un archivo CSV\./);

    await choose(page, "x", "hoja.xlsx");
    await simulateButton(page).click();
    await expect(fileField(page)).toHaveAccessibleDescription(/extensión \.csv/);
    expect(calls.filter((call) => call.startsWith("POST"))).toEqual([]);
  });

  test("lists in plain words what is wrong with a file the server could not read, and moves the focus to it", async ({ page, scenarioId }) => {
    await openImports(page, scenarioId);

    await choose(page, "legacyId,firstName,hobby\n1,Ana,x");
    await simulateButton(page).click();

    const alert = page.getByRole("alert").filter({ hasText: "No se pudo completar la operación" });
    await expect(alert).toBeFocused();
    await expect(alert).toContainText("La columna número 3 de la cabecera no está en la plantilla");
    await expect(alert).toContainText("Falta la columna obligatoria «lastName» en la cabecera.");
    await expect(alert).toContainText("Falta la columna obligatoria «birthDate» en la cabecera.");
    await expect(summaryHeading(page)).toBeHidden();
  });

  test("tells a 403 in plain words, changes nothing and leaves the file to try again", async ({ page, scenarioId }) => {
    await openImports(page, scenarioId, "imports-forbidden");

    await choose(page, csvOf(3));
    await simulateButton(page).click();

    await expect(page.getByRole("alert").filter({ hasText: "No tiene permiso para realizar esta acción." })).toBeFocused();
    await expect(summaryHeading(page)).toBeHidden();
    await expect(dialog(page)).toHaveCount(0);
    await expect(page.getByRole("cell", { name: "Aplicada" })).toBeVisible();
  });

  test("downloads the empty template with the header of the backend", async ({ page, scenarioId }) => {
    await openImports(page, scenarioId);

    const [download] = await Promise.all([page.waitForEvent("download"), page.getByRole("button", { name: "Descargar la plantilla vacía" }).click()]);

    expect(download.suggestedFilename()).toBe("plantilla-estudiantes.csv");
    const stream = await download.createReadStream();
    const chunks: Buffer[] = [];
    for await (const chunk of stream) chunks.push(chunk as Buffer);
    expect(Buffer.concat(chunks).toString("utf8")).toBe("legacyId,firstName,lastName,birthDate,nationalId,sex\r\n");
  });
});
