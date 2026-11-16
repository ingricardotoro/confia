import type { Locator, Page } from "@playwright/test";
import { dialog, openPriceListDetail, openPriceLists, SAVE_TIMEOUT, SEEDED_LISTS, test } from "../../support/catalog.ts";
import { expect } from "../../support/scenario.ts";

// The price lists with the keyboard alone (docs/ui-ux/03 section 9): Tab, Shift+Tab, Enter, Space, the arrow keys, Escape
// and typing are the only input after the page opens, the focus is always visible, the dialog keeps it inside, and it
// goes back to the control that opened the dialog. Chromium, the production build and the SIMULATED API.
const MAX_TABS = 80;

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

test.describe("the price lists with the keyboard only", () => {
  test("creates a list with the keyboard: Enter opens the dialog, the fields follow in the visual order, and the focus returns to the opener", async ({ page, scenarioId }) => {
    await openPriceLists(page, scenarioId, "catalog-price-lists-empty");
    const opener = page.getByRole("button", { name: "Nueva lista de precios" });
    await page.locator("body").press("Tab");
    await tabTo(page, opener);

    await page.keyboard.press("Enter");

    await expect(dialog(page)).toBeVisible();
    await expect(dialog(page).getByLabel(/^Año lectivo/)).toBeFocused();
    await expectVisibleFocus(page);
    // The first arrow leaves the placeholder, the next ones choose a year; typing its name is the same input.
    await page.keyboard.type("Año lectivo 2026");
    await expect(dialog(page).getByLabel(/^Año lectivo/)).toHaveValue(/00000000-0000-4000-8000-0000000010/);
    await page.keyboard.press("Tab");
    await expect(dialog(page).getByLabel(/^Ámbito/)).toBeFocused();
    await expectVisibleFocus(page);
    await page.keyboard.press("Tab");
    await expect(dialog(page).getByLabel(/^Nombre/)).toBeFocused();
    await page.keyboard.type("2027 — Institución");
    await page.keyboard.press("Tab");
    await expect(dialog(page).getByRole("button", { name: "Cancelar" })).toBeFocused();
    await page.keyboard.press("Tab");
    await expect(dialog(page).getByRole("button", { name: "Guardar" })).toBeFocused();
    await expectVisibleFocus(page);

    await page.keyboard.press("Enter");

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(page.getByRole("row", { name: /2027 — Institución/ })).toBeVisible();
    await expect(page.getByText("Se creó el registro «2027 — Institución».")).toBeVisible();
    await expect(opener).toBeFocused();
  });

  test("an empty form puts its errors in front of the person without leaving the keyboard, and the summary leads to the first field", async ({ page, scenarioId }) => {
    await openPriceLists(page, scenarioId);
    await tabTo(page, page.getByRole("button", { name: "Nueva lista de precios" }));
    await page.keyboard.press("Space");
    await expect(dialog(page)).toBeVisible();
    await tabTo(page, dialog(page).getByRole("button", { name: "Guardar" }));

    await page.keyboard.press("Space");

    await expect(dialog(page).getByRole("group", { name: "Hay 2 campos con errores." })).toBeVisible();
    await expectVisibleFocus(page);
    await page.keyboard.press("Tab");
    await page.keyboard.press("Enter");
    await expect(dialog(page).getByLabel(/^Año lectivo/)).toBeFocused();
  });

  test("opens the detail from the keyboard with Enter on the name of a list", async ({ page, scenarioId }) => {
    await openPriceLists(page, scenarioId);
    await tabTo(page, page.getByRole("link", { name: "2026 — Institución" }));

    await page.keyboard.press("Enter");

    await expect(page).toHaveURL(new RegExp(`/catalog/price-lists/${SEEDED_LISTS.institution2026}$`));
    await expect(page.getByRole("heading", { level: 2, name: "2026 — Institución" })).toBeVisible();
  });

  test("sets a price with the keyboard, the focus stays inside the dialog, and it returns to the button that opened it", async ({ page, scenarioId }) => {
    await openPriceListDetail(page, scenarioId, SEEDED_LISTS.first2026, "2026 — Primero");
    const opener = page.getByRole("button", { name: "Nuevo precio / nueva vigencia" });
    await page.locator("body").press("Tab");
    await tabTo(page, opener);
    await page.keyboard.press("Enter");
    await expect(dialog(page)).toBeVisible();
    await expect(dialog(page).getByLabel(/^Concepto/)).toBeFocused();

    await page.keyboard.type("Colegiatura");
    await expect(dialog(page).getByLabel(/^Concepto/)).toHaveValue("00000000-0000-4000-8000-000000007001");
    await page.keyboard.press("Tab");
    await expect(dialog(page).getByLabel(/^Importe/)).toBeFocused();
    await page.keyboard.type("1500.00");
    await page.keyboard.press("Tab");
    await expect(dialog(page).getByLabel(/^Moneda/)).toBeFocused();
    await page.keyboard.press("Tab");
    await expect(dialog(page).getByLabel(/^Vigente desde/)).toBeFocused();
    await expectVisibleFocus(page);
    await page.keyboard.type("01012026");
    await expect(dialog(page).getByLabel(/^Vigente desde/)).toHaveValue("2026-01-01");
    // The fields of a date are three stops and the button of its calendar a fourth, which the browser draws its own way
    // (it does not answer `:focus`), so from here only that the focus stays inside the dialog is checked until the buttons.
    const submit = dialog(page).getByRole("button", { name: "Guardar precio" });
    for (let presses = 0; presses < 20 && !(await submit.evaluate((element) => element === document.activeElement)); presses += 1) {
      await page.keyboard.press("Tab");
      expect(await dialog(page).evaluate((element) => element.contains(document.activeElement)), `focus after ${String(presses + 1)} Tab`).toBe(true);
    }
    await expect(submit).toBeFocused();
    await expectVisibleFocus(page);

    await page.keyboard.press("Enter");

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(page.getByRole("table", { name: "Vigencias de Colegiatura" })).toBeVisible();
    await expect(opener).toBeFocused();
  });

  test("Escape closes the dialog of a price and returns the focus to the opener, and archive works with Space", async ({ page, scenarioId }) => {
    await openPriceListDetail(page, scenarioId, SEEDED_LISTS.institution2026, "2026 — Institución");
    const opener = page.getByRole("button", { name: "Nuevo precio / nueva vigencia" });
    await page.locator("body").press("Tab");
    await tabTo(page, opener);
    await page.keyboard.press("Space");
    await expect(dialog(page)).toBeVisible();

    await page.keyboard.press("Escape");

    await expect(dialog(page)).toBeHidden();
    await expect(opener).toBeFocused();
    // The archive button comes before the button of a new price, so it is one step back.
    const archive = page.getByRole("button", { name: "Archivar lista 2026 — Institución" });
    await page.keyboard.press("Shift+Tab");
    await expect(archive).toBeFocused();
    await expectVisibleFocus(page);
    await page.keyboard.press("Space");
    await expect(page.getByText("Se archivó la lista «2026 — Institución».")).toBeVisible();
  });
});
