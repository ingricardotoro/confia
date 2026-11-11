import type { Locator, Page } from "@playwright/test";
import { dialog, openStudentsPage, SAVE_TIMEOUT, test } from "../../support/students.ts";
import { expect } from "../../support/scenario.ts";

// Registering and editing a student with the keyboard alone (docs/ui-ux/03 section 9): Tab, Shift+Tab, Enter, Space,
// Escape, the arrow keys and typing are the only input after the page opens, the focus is always visible, the dialog
// keeps it inside, and it goes back to the control that opened it. Chromium, the production build and the SIMULATED API.
const MAX_TABS = 40;

/** The element with the focus is on the screen and draws an outline (the level 1 heading, focused by the app, draws none by design). */
async function expectVisibleFocus(page: Page): Promise<Locator> {
  const focused = page.locator(":focus");
  await expect(focused, "something has the focus").toHaveCount(1);
  await expect(focused, "the focused element is visible").toBeVisible();
  const ring = await focused.evaluate((element) => {
    const style = getComputedStyle(element);
    return { tag: element.tagName, drawn: style.outlineStyle !== "none" && parseFloat(style.outlineWidth) > 0 };
  });
  if (ring.tag !== "H1") expect([ring.tag, ring.drawn]).toEqual([ring.tag, true]);
  return focused;
}

/**
 * Presses Tab until the control is the one with the focus, checking the focus after each press; fails if it never arrives.
 * The focus inside a date field (its day, month and year segments) lives in the browser's own shadow tree, which `:focus`
 * does not reach, so there the check is that the field is the one that holds it; the browser draws the segment itself.
 */
async function tabTo(page: Page, target: Locator): Promise<void> {
  for (let presses = 0; presses < MAX_TABS; presses += 1) {
    if (await target.evaluate((element) => element === document.activeElement)) return;
    await page.keyboard.press("Tab");
    const inDateField = await page.evaluate(() => document.activeElement instanceof HTMLInputElement && document.activeElement.type === "date");
    if (!inDateField) await expectVisibleFocus(page);
  }
  throw new Error(`The focus did not reach the control in ${String(MAX_TABS)} presses of Tab.`);
}

test.describe("the students registry with the keyboard only", () => {
  test("opens the create dialog with Enter, fills and submits it with the keyboard, and the focus returns to the opener", async ({ page }) => {
    await openStudentsPage(page);
    const opener = page.getByRole("button", { name: "Nuevo estudiante" });
    // From here on the mouse is never used.
    await page.locator("body").press("Tab");
    await tabTo(page, opener);

    await page.keyboard.press("Enter");

    await expect(dialog(page)).toBeVisible();
    // The first field holds the focus, and the order goes down the form in the visual order.
    await expect(dialog(page).getByLabel(/^Código/)).toBeFocused();
    await expectVisibleFocus(page);
    await page.keyboard.press("Tab");
    await expect(dialog(page).getByLabel(/^Nombres/)).toBeFocused();
    await page.keyboard.type("Teclado");
    await page.keyboard.press("Tab");
    await expect(dialog(page).getByLabel(/^Apellidos/)).toBeFocused();
    await page.keyboard.type("Zuniga");
    await page.keyboard.press("Tab");
    // The date is typed by segments (day, month, year in the Spanish order), which the field moves through by itself.
    await expect(dialog(page).getByLabel(/^Fecha de nacimiento/)).toBeFocused();
    await page.keyboard.type("09032014");
    const sex = dialog(page).getByLabel(/^Sexo/);
    await tabTo(page, sex);
    await page.keyboard.press("ArrowDown");
    await expect(sex).toHaveValue("F");
    await page.keyboard.press("Tab");
    await expect(dialog(page).getByLabel(/^Documento de identidad/)).toBeFocused();
    await page.keyboard.type("0801-2011-12121");
    await page.keyboard.press("Tab");
    await expect(dialog(page).getByRole("button", { name: "Cancelar" })).toBeFocused();
    await page.keyboard.press("Tab");
    await expect(dialog(page).getByRole("button", { name: "Guardar" })).toBeFocused();
    await expectVisibleFocus(page);

    await page.keyboard.press("Enter");

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(page.getByText("Se creó el registro «Teclado Zuniga».")).toBeVisible();
    await expect(opener).toBeFocused();
  });

  test("an empty form puts its errors in front of the person without leaving the keyboard", async ({ page }) => {
    await openStudentsPage(page);
    await tabTo(page, page.getByRole("button", { name: "Nuevo estudiante" }));
    await page.keyboard.press("Space");
    await expect(dialog(page)).toBeVisible();
    await tabTo(page, dialog(page).getByRole("button", { name: "Guardar" }));

    await page.keyboard.press("Space");

    await expect(dialog(page).getByRole("group", { name: "Hay 4 campos con errores." })).toBeVisible();
    await expect(dialog(page)).toBeVisible();
    // The summary takes the focus, and its first link leads to the first field with an error.
    await expectVisibleFocus(page);
    await page.keyboard.press("Tab");
    await page.keyboard.press("Enter");
    await expect(dialog(page).getByLabel(/^Nombres/)).toBeFocused();
  });

  test("keeps the focus inside the dialog while it is open, and Escape closes it and returns the focus to the opener", async ({ page }) => {
    await openStudentsPage(page);
    const opener = page.getByRole("button", { name: "Nuevo estudiante" });
    await tabTo(page, opener);
    await page.keyboard.press("Enter");
    await expect(dialog(page)).toBeVisible();

    for (let presses = 0; presses < 16; presses += 1) {
      await page.keyboard.press("Tab");
      expect(await dialog(page).evaluate((element) => element.contains(document.activeElement)), `focus after ${String(presses + 1)} Tab`).toBe(true);
    }
    await page.keyboard.press("Shift+Tab");
    expect(await dialog(page).evaluate((element) => element.contains(document.activeElement))).toBe(true);
    await page.keyboard.press("Escape");

    await expect(dialog(page)).toBeHidden();
    await expect(opener).toBeFocused();
  });

  test("edits a row from the keyboard: the removal of the document is a box that Space checks, and the focus returns to the row", async ({ page }) => {
    await openStudentsPage(page);
    // Luis Pérez has a document, so the dialog offers the box that removes it.
    await page.getByRole("searchbox", { name: "Buscar" }).fill("Pérez");
    await expect(page.getByRole("button", { name: "Editar Luis Pérez" })).toBeVisible();
    const edit = page.getByRole("button", { name: "Editar Luis Pérez" });
    await tabTo(page, edit);

    await page.keyboard.press("Enter");

    await expect(dialog(page).getByLabel(/^Nombres/)).toHaveValue("Luis");
    const remove = dialog(page).getByRole("checkbox", { name: "Quitar documento" });
    await tabTo(page, remove);
    await page.keyboard.press("Space");
    await expect(remove).toBeChecked();
    await expect(dialog(page).getByLabel("Reemplazar documento")).toBeDisabled();
    await page.keyboard.press("Space");
    await expect(remove).not.toBeChecked();
    await page.keyboard.press("Escape");

    await expect(dialog(page)).toBeHidden();
    await expect(edit).toBeFocused();
  });
});
