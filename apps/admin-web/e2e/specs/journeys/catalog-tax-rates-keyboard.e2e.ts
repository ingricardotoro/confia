import type { Locator, Page } from "@playwright/test";
import { dialog, openTaxRateDetail, openTaxRates, SAVE_TIMEOUT, SEEDED_RATES, test } from "../../support/catalog.ts";
import { expect } from "../../support/scenario.ts";

// The tax rates with the keyboard alone (docs/ui-ux/03 section 9): Tab, Shift+Tab, Enter, Space, the arrow keys, Escape
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

test.describe("the tax rates with the keyboard only", () => {
  test("creates a rate with the keyboard: Enter opens the dialog, the fields follow in the visual order, and the focus returns to the opener", async ({ page, scenarioId }) => {
    await openTaxRates(page, scenarioId, "catalog-tax-rates-empty");
    const opener = page.getByRole("button", { name: "Nueva tasa" });
    await page.locator("body").press("Tab");
    await tabTo(page, opener);

    await page.keyboard.press("Enter");

    await expect(dialog(page)).toBeVisible();
    await expect(dialog(page).getByLabel(/^Código/)).toBeFocused();
    await expectVisibleFocus(page);
    await page.keyboard.type("ISV");
    await page.keyboard.press("Tab");
    await expect(dialog(page).getByLabel(/^Nombre/)).toBeFocused();
    await expectVisibleFocus(page);
    await page.keyboard.type("Impuesto sobre ventas");
    await page.keyboard.press("Tab");
    await expect(dialog(page).getByRole("button", { name: "Cancelar" })).toBeFocused();
    await page.keyboard.press("Tab");
    await expect(dialog(page).getByRole("button", { name: "Guardar" })).toBeFocused();
    await expectVisibleFocus(page);

    await page.keyboard.press("Enter");

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(page.getByRole("row", { name: /ISV/ })).toBeVisible();
    await expect(page.getByText("Se creó el registro «Impuesto sobre ventas».")).toBeVisible();
    await expect(opener).toBeFocused();
  });

  test("an empty form puts its errors in front of the person without leaving the keyboard, and the summary leads to the first field", async ({ page, scenarioId }) => {
    await openTaxRates(page, scenarioId);
    await tabTo(page, page.getByRole("button", { name: "Nueva tasa" }));
    await page.keyboard.press("Space");
    await expect(dialog(page)).toBeVisible();
    await tabTo(page, dialog(page).getByRole("button", { name: "Guardar" }));

    await page.keyboard.press("Space");

    await expect(dialog(page).getByRole("group", { name: "Hay 2 campos con errores." })).toBeVisible();
    await expectVisibleFocus(page);
    await page.keyboard.press("Tab");
    await page.keyboard.press("Enter");
    await expect(dialog(page).getByLabel(/^Código/)).toBeFocused();
  });

  test("opens the detail from the keyboard with Enter on the code of a rate", async ({ page, scenarioId }) => {
    await openTaxRates(page, scenarioId);
    await tabTo(page, page.getByRole("link", { name: "IMP-A" }));

    await page.keyboard.press("Enter");

    await expect(page).toHaveURL(new RegExp(`/catalog/tax-rates/${SEEDED_RATES.a}$`));
    await expect(page.getByRole("heading", { level: 2, name: "Impuesto de prueba A" })).toBeVisible();
  });

  test("deactivates a rate from its row with Space on the button", async ({ page, scenarioId }) => {
    await openTaxRates(page, scenarioId);
    const toggle = page.getByRole("button", { name: "Desactivar Impuesto de prueba A" });
    await tabTo(page, toggle);
    await expectVisibleFocus(page);

    await page.keyboard.press("Space");

    await expect(page.getByText("Se desactivó «Impuesto de prueba A».")).toBeVisible();
  });

  test("registers a validity with the keyboard, the focus stays inside the dialog, and it returns to the button that opened it", async ({ page, scenarioId }) => {
    await openTaxRateDetail(page, scenarioId, SEEDED_RATES.b, "Impuesto de prueba B");
    const opener = page.getByRole("button", { name: "Nueva vigencia" });
    await page.locator("body").press("Tab");
    await tabTo(page, opener);
    await page.keyboard.press("Enter");
    await expect(dialog(page)).toBeVisible();
    await expect(dialog(page).getByLabel(/^Porcentaje/)).toBeFocused();

    await page.keyboard.type("2.5");
    await page.keyboard.press("Tab");
    await expect(dialog(page).getByLabel(/^Vigente desde/)).toBeFocused();
    await expectVisibleFocus(page);
    await page.keyboard.type("01012027");
    await expect(dialog(page).getByLabel(/^Vigente desde/)).toHaveValue("2027-01-01");
    // The fields of a date are three stops and the button of its calendar a fourth, which the browser draws its own way
    // (it does not answer `:focus`), so from here only that the focus stays inside the dialog is checked until the buttons.
    const submit = dialog(page).getByRole("button", { name: "Guardar vigencia" });
    for (let presses = 0; presses < 20 && !(await submit.evaluate((element) => element === document.activeElement)); presses += 1) {
      await page.keyboard.press("Tab");
      expect(await dialog(page).evaluate((element) => element.contains(document.activeElement)), `focus after ${String(presses + 1)} Tab`).toBe(true);
    }
    await expect(submit).toBeFocused();
    await expectVisibleFocus(page);

    await page.keyboard.press("Enter");

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(page.getByText(/Se registró la vigencia de «Impuesto de prueba B»/)).toBeVisible();
    await expect(opener).toBeFocused();
  });

  test("Escape closes the dialog of a validity and returns the focus to the opener, and deactivate works with Space", async ({ page, scenarioId }) => {
    await openTaxRateDetail(page, scenarioId, SEEDED_RATES.a, "Impuesto de prueba A");
    const opener = page.getByRole("button", { name: "Nueva vigencia" });
    await page.locator("body").press("Tab");
    await tabTo(page, opener);
    await page.keyboard.press("Space");
    await expect(dialog(page)).toBeVisible();

    await page.keyboard.press("Escape");

    await expect(dialog(page)).toBeHidden();
    await expect(opener).toBeFocused();
    // The button of the state comes before the button of a new validity, so it is one step back.
    const toggle = page.getByRole("button", { name: "Desactivar tasa Impuesto de prueba A" });
    await page.keyboard.press("Shift+Tab");
    await expect(toggle).toBeFocused();
    await expectVisibleFocus(page);
    await page.keyboard.press("Space");
    await expect(page.getByText("Se desactivó la tasa «Impuesto de prueba A».")).toBeVisible();
  });

  test("the rate field of a concept follows the Gravado checkbox in the tab order, and a rate is chosen with the arrow keys", async ({ page, scenarioId }) => {
    await openTaxRates(page, scenarioId);
    await page.getByRole("navigation", { name: "Vistas del catálogo" }).getByRole("link", { name: "Conceptos de pago" }).click();
    await page.getByRole("button", { name: "Nuevo concepto" }).click();
    await expect(dialog(page)).toBeVisible();
    const gravado = dialog(page).getByRole("checkbox", { name: "Gravado" });
    await tabTo(page, gravado);
    await page.keyboard.press("Space");
    await expect(gravado).toBeChecked();
    const rate = dialog(page).getByLabel(/^Tasa de impuesto/);
    await expect(rate.locator("option", { hasText: "IMP-A — Impuesto de prueba A" })).toBeAttached();

    await page.keyboard.press("Tab");

    await expect(rate).toBeFocused();
    await expectVisibleFocus(page);
    await page.keyboard.press("ArrowDown");
    await expect(rate).toHaveValue(SEEDED_RATES.a);
    await page.keyboard.press("Tab");
    await expect(dialog(page).getByRole("button", { name: "Cancelar" })).toBeFocused();
  });
});
