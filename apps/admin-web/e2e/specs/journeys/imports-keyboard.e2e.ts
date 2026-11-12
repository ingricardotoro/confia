import { expect } from "../../support/scenario.ts";
import { choose, csvOf, dialog, fileField, openImports, resultHeading, simulateButton, summaryHeading, test } from "../../support/imports.ts";

// Keyboard only, against the SIMULATED API: reach the file field with Tab, choose a file, simulate with Enter, page the
// rejections, open the confirmation, see the focus trapped inside it, cancel with Escape and get the focus back, and apply.
test.describe("the import screen by keyboard", () => {
  test("goes from the file field to the result without the pointer, and the focus follows what has to be read next", async ({ page, scenarioId }) => {
    await openImports(page, scenarioId, "imports-empty");
    await fileField(page).focus();
    await expect(fileField(page)).toBeFocused();
    // The file chooser of the browser is the person's own: the test hands the file to the field and goes on with the keys.
    await choose(page, csvOf(22, "L90,,Apellido,2013-05-01,,F", "L91,Nombre,Apellido,2013-02-30,,F"));

    await page.keyboard.press("Tab");
    await expect(simulateButton(page)).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(summaryHeading(page)).toBeFocused();

    const apply = page.getByRole("button", { name: "Aplicar la importación" });
    await apply.focus();
    await page.keyboard.press("Enter");
    await expect(dialog(page)).toBeVisible();
    for (let step = 0; step < 12; step += 1) {
      await page.keyboard.press("Tab");
      // The focus never leaves the confirmation.
      expect(await page.evaluate(() => document.activeElement?.closest('[role="dialog"]') !== null)).toBe(true);
    }
    await page.keyboard.press("Escape");
    await expect(dialog(page)).toBeHidden();
    await expect(apply).toBeFocused();

    await page.keyboard.press("Enter");
    await dialog(page).getByRole("button", { name: "Crear 22 estudiantes" }).focus();
    await page.keyboard.press("Enter");
    await expect(resultHeading(page)).toBeFocused();
    await page.keyboard.press("Tab");
    await expect(page.getByRole("button", { name: "Importar otro archivo" })).toBeFocused();
  });

  test("pages the rejections with the keyboard, ten at a time", async ({ page, scenarioId }) => {
    await openImports(page, scenarioId, "imports-empty");
    const rows = Array.from({ length: 12 }, (_, index) => `L${String(900 + index)},,Apellido,2013-05-01,,F`);
    await choose(page, csvOf(1, ...rows));
    await simulateButton(page).focus();
    await page.keyboard.press("Enter");
    await expect(summaryHeading(page)).toBeFocused();
    const table = page.getByRole("table", { name: /Filas del archivo con su línea/ });
    await expect(table.getByRole("row")).toHaveCount(10 + 1);

    await page.getByRole("button", { name: "Siguiente" }).first().focus();
    await page.keyboard.press("Enter");

    await expect(table.getByRole("row")).toHaveCount(2 + 1);
  });
});
