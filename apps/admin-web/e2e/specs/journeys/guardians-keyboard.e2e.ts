import { expect } from "../../support/scenario.ts";
import { addBox, dialog, field, linkRow, openLinks, openStudentsForLinks, saveLinks, SAVE_TIMEOUT, resultNotice, openGuardians, test } from "../../support/guardians.ts";

// Keyboard only: register a guardian with Tab and Enter and get the focus back, and manage the guardians of a student without
// the pointer (search, arrows, Enter, Tab to the row, Escape traps and returns the focus). Against the SIMULATED API.
test.describe("the guardians screens by keyboard", () => {
  test("registers a guardian with the keyboard, traps the focus inside the dialog, and returns it to the button that opened it", async ({ page, scenarioId }) => {
    await openGuardians(page, scenarioId);
    const opener = page.getByRole("button", { name: "Nuevo encargado" });
    await opener.focus();

    await page.keyboard.press("Enter");
    await expect(dialog(page)).toBeVisible();
    await expect(field(page, "Nombres")).toBeFocused();
    await page.keyboard.type("Eva");
    await page.keyboard.press("Tab");
    await page.keyboard.type("Rivas");
    await page.keyboard.press("Enter");

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Se creó el registro «Eva Rivas».");
    await expect(opener).toBeFocused();
  });

  test("escape closes the dialog and gives the focus back to the button of the row", async ({ page, scenarioId }) => {
    await openStudentsForLinks(page, scenarioId);
    await page.getByRole("searchbox", { name: "Buscar" }).fill("Aguilar");
    // The search settles first: a list that is read again would replace the button that opened the dialog.
    await expect(page).toHaveURL(/q=Aguilar/);
    await expect(page.getByRole("table", { name: "Estudiantes de la institución" })).not.toHaveAttribute("aria-busy", "true");
    await expect(page.getByRole("row")).toHaveCount(3);
    const opener = page.getByRole("button", { name: "Encargados de Ana Aguilar" });
    await opener.focus();

    await page.keyboard.press("Enter");
    await expect(dialog(page).getByText(/Suma guardada actualmente/)).toBeVisible();
    for (let step = 0; step < 40; step += 1) {
      await page.keyboard.press("Tab");
      // The focus never leaves the dialog.
      expect(await page.evaluate(() => document.activeElement?.closest('[role="dialog"]') !== null)).toBe(true);
    }
    await page.keyboard.press("Escape");

    await expect(dialog(page)).toBeHidden();
    await expect(opener).toBeFocused();
  });

  test("adds a guardian and saves the set without the pointer", async ({ page, scenarioId }) => {
    await openStudentsForLinks(page, scenarioId);
    await openLinks(page, "Alvarado", "María Alvarado");

    await addBox(page).focus();
    await page.keyboard.type("Cruz");
    await expect(dialog(page).getByRole("option", { name: /Marta Cruz/ })).toBeVisible();
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("Enter");
    const marta = linkRow(page, "Marta Cruz · P-000005");
    await expect(marta.getByLabel(/^Parentesco/)).toBeFocused();
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("Tab");
    await page.keyboard.type("100");
    await page.keyboard.press("Enter");

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(resultNotice(page)).toContainText("Se guardaron los encargados de «María Alvarado». Suma: 100.00 %.");
  });

  test("the save button is the way to send: Enter in the search box only adds, and never sends the set", async ({ page, scenarioId }) => {
    await openStudentsForLinks(page, scenarioId);
    await openLinks(page, "Aguilar", "Ana Aguilar");

    await addBox(page).focus();
    await page.keyboard.type("zzzz");
    await page.keyboard.press("Enter");

    await expect(dialog(page)).toBeVisible();
    await expect(saveLinks(page)).toBeVisible();
  });
});
