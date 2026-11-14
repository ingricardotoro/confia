import type { Locator, Page } from "@playwright/test";
import { dialog, goToView, openOrganization, SAVE_TIMEOUT, test } from "../../support/organization.ts";
import { expect } from "../../support/scenario.ts";

// Creating a modality with the keyboard alone (docs/ui-ux/03 section 9): Tab, Shift+Tab, Enter, Space, Escape and
// typing are the only input after the page opens, the focus is always visible, the dialog keeps it inside, and it
// goes back to the control that opened the dialog. Chromium, the production build and the SIMULATED API.
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

/** Presses Tab until the control is the one with the focus, checking the focus after each press; fails if it never arrives. */
async function tabTo(page: Page, target: Locator): Promise<void> {
  for (let presses = 0; presses < MAX_TABS; presses += 1) {
    if (await target.evaluate((element) => element === document.activeElement)) return;
    await page.keyboard.press("Tab");
    await expectVisibleFocus(page);
  }
  throw new Error(`The focus did not reach the control in ${String(MAX_TABS)} presses of Tab.`);
}

test.describe("the academic structure with the keyboard only", () => {
  test("opens the create dialog with Enter, fills and submits it with the keyboard, and the focus returns to the opener", async ({ page }) => {
    await openOrganization(page);
    await goToView(page, "modalities");
    const opener = page.getByRole("button", { name: "Nueva modalidad" });
    // From here on the mouse is never used.
    await page.locator("body").press("Tab");
    await tabTo(page, opener);

    await page.keyboard.press("Enter");

    await expect(dialog(page)).toBeVisible();
    // The first field holds the focus, and the order goes down the form to the buttons, in the visual order.
    await expect(dialog(page).getByLabel(/^Código/)).toBeFocused();
    await expectVisibleFocus(page);
    await page.keyboard.type("BAC");
    await page.keyboard.press("Tab");
    await expect(dialog(page).getByLabel(/^Nombre/)).toBeFocused();
    await page.keyboard.type("Bachillerato");
    await page.keyboard.press("Tab");
    await expect(dialog(page).getByRole("button", { name: "Cancelar" })).toBeFocused();
    await page.keyboard.press("Tab");
    await expect(dialog(page).getByRole("button", { name: "Guardar" })).toBeFocused();
    await expectVisibleFocus(page);

    await page.keyboard.press("Enter");

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(page.getByRole("row", { name: /Bachillerato/ })).toBeVisible();
    await expect(page.getByText("Se creó el registro «Bachillerato».")).toBeVisible();
    await expect(opener).toBeFocused();
  });

  test("submits with Space on the button, and an empty form puts its errors in front of the person without leaving the keyboard", async ({ page }) => {
    await openOrganization(page);
    await goToView(page, "modalities");
    await tabTo(page, page.getByRole("button", { name: "Nueva modalidad" }));
    await page.keyboard.press("Space");
    await expect(dialog(page)).toBeVisible();
    await tabTo(page, dialog(page).getByRole("button", { name: "Guardar" }));

    await page.keyboard.press("Space");

    await expect(dialog(page).getByRole("group", { name: "Hay 2 campos con errores." })).toBeVisible();
    await expect(dialog(page)).toBeVisible();
    // The summary takes the focus, and its first link leads to the first field with an error.
    await expectVisibleFocus(page);
    await page.keyboard.press("Tab");
    await page.keyboard.press("Enter");
    await expect(dialog(page).getByLabel(/^Código/)).toBeFocused();
  });

  test("keeps the focus inside the dialog while it is open, and Escape closes it and returns the focus to the opener", async ({ page }) => {
    await openOrganization(page);
    await goToView(page, "modalities");
    const opener = page.getByRole("button", { name: "Nueva modalidad" });
    await tabTo(page, opener);
    await page.keyboard.press("Enter");
    await expect(dialog(page)).toBeVisible();

    for (let presses = 0; presses < 12; presses += 1) {
      await page.keyboard.press("Tab");
      expect(await dialog(page).evaluate((element) => element.contains(document.activeElement)), `focus after ${String(presses + 1)} Tab`).toBe(true);
    }
    await page.keyboard.press("Shift+Tab");
    expect(await dialog(page).evaluate((element) => element.contains(document.activeElement))).toBe(true);
    await page.keyboard.press("Escape");

    await expect(dialog(page)).toBeHidden();
    await expect(opener).toBeFocused();
  });

  test("edits a row from the keyboard, and the focus returns to the button of that row", async ({ page }) => {
    await openOrganization(page);
    await goToView(page, "modalities");
    const edit = page.getByRole("button", { name: "Editar Secundaria" });
    await tabTo(page, edit);

    await page.keyboard.press("Enter");

    await expect(dialog(page).getByLabel(/^Nombre/)).toHaveValue("Secundaria");
    await page.keyboard.press("Escape");
    await expect(dialog(page)).toBeHidden();
    await expect(edit).toBeFocused();
  });
});
