import type { Locator, Page } from "@playwright/test";
import { dialog, openPlanDetail, openPlans, SAVE_TIMEOUT, SEEDED_PLANS, test } from "../../support/charges.ts";
import { expect } from "../../support/scenario.ts";

// The plans with the keyboard alone (docs/ui-ux/03 section 9): Tab, Shift+Tab, Enter, Space, the arrow keys, Escape and typing
// are the only input after the page opens, the focus is always visible, the dialog keeps it inside, and it goes back to the
// control that opened the dialog. Chromium, the production build and the SIMULATED API.
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

test.describe("the plans with the keyboard only", () => {
  test("creates a plan with the keyboard: Enter opens the dialog, the fields follow in the visual order, and the focus returns to the opener", async ({ page, scenarioId }) => {
    await openPlans(page, scenarioId, "charges-empty");
    const opener = page.getByRole("button", { name: "Nuevo plan de cobro" });
    await page.locator("body").press("Tab");
    await tabTo(page, opener);

    await page.keyboard.press("Enter");

    await expect(dialog(page)).toBeVisible();
    await expect(dialog(page).getByLabel(/^Nombre/)).toBeFocused();
    await expectVisibleFocus(page);
    await page.keyboard.type("Primaria 2027");
    await page.keyboard.press("Tab");
    await expect(dialog(page).getByLabel(/^Año lectivo/)).toBeFocused();
    // Typing the name of an option chooses it. Only its first letter is typed: Playwright sends a character that is not on the
    // US layout (the "ñ" of "Año") as a text insertion with no key event, and Chromium then moves the focus out of the select,
    // which no person typing on a keyboard of this language does.
    await page.keyboard.type("A");
    await expect(dialog(page).getByLabel(/^Año lectivo/)).toHaveValue(/00000000-0000-4000-8000-0000000010/);
    await page.keyboard.press("Tab");
    await expect(dialog(page).getByLabel(/^Ámbito/)).toBeFocused();
    await expectVisibleFocus(page);
    await page.keyboard.press("Tab");
    await expect(dialog(page).getByLabel(/^Grado/)).toBeFocused();
    await page.keyboard.type("Primero");
    await page.keyboard.press("Tab");
    await expect(dialog(page).getByLabel(/^Periodicidad/)).toBeFocused();
    await page.keyboard.press("Tab");
    await expect(dialog(page).getByLabel(/^Día de vencimiento/)).toBeFocused();
    await page.keyboard.type("5");
    await page.keyboard.press("Tab");
    await expect(dialog(page).getByRole("button", { name: "Cancelar" })).toBeFocused();
    await page.keyboard.press("Tab");
    await expect(dialog(page).getByRole("button", { name: "Guardar" })).toBeFocused();
    await expectVisibleFocus(page);

    await page.keyboard.press("Enter");

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(page.getByRole("row", { name: /Primaria 2027/ })).toBeVisible();
    await expect(page.getByText("Se creó el registro «Primaria 2027».")).toBeVisible();
    await expect(opener).toBeFocused();
  });

  test("an empty form puts its errors in front of the person without leaving the keyboard, and the summary leads to the first field", async ({ page, scenarioId }) => {
    await openPlans(page, scenarioId);
    await tabTo(page, page.getByRole("button", { name: "Nuevo plan de cobro" }));
    await page.keyboard.press("Space");
    await expect(dialog(page)).toBeVisible();
    await tabTo(page, dialog(page).getByRole("button", { name: "Guardar" }));

    await page.keyboard.press("Space");

    await expect(dialog(page).getByRole("group", { name: "Hay 4 campos con errores." })).toBeVisible();
    await expectVisibleFocus(page);
    await page.keyboard.press("Tab");
    await page.keyboard.press("Enter");
    await expect(dialog(page).getByLabel(/^Nombre/)).toBeFocused();
  });

  test("opens the detail from the keyboard with Enter on the name of a plan", async ({ page, scenarioId }) => {
    await openPlans(page, scenarioId);
    await page.getByRole("group", { name: "Filtros" }).getByLabel("Estado").selectOption({ label: "Activo" });
    await tabTo(page, page.getByRole("link", { name: "Primaria 2026" }));

    await page.keyboard.press("Enter");

    await expect(page).toHaveURL(new RegExp(`/charges/plans/${SEEDED_PLANS.active}$`));
    await expect(page.getByRole("heading", { level: 2, name: "Primaria 2026" })).toBeVisible();
  });

  test("adds a concept with the keyboard, the focus stays inside the dialog, and it returns to the button that opened it", async ({ page, scenarioId }) => {
    await openPlanDetail(page, scenarioId, SEEDED_PLANS.draftEmpty, "Secundaria 2026");
    const opener = page.getByRole("button", { name: "Agregar concepto" });
    await page.locator("body").press("Tab");
    await tabTo(page, opener);
    await page.keyboard.press("Enter");
    await expect(dialog(page)).toBeVisible();
    await expect(dialog(page).getByLabel(/^Concepto/)).toBeFocused();

    await page.keyboard.type("Colegiatura");
    await expect(dialog(page).getByLabel(/^Concepto/)).toHaveValue("00000000-0000-4000-8000-000000007001");
    await page.keyboard.press("Tab");
    await expect(dialog(page).getByLabel(/^Cantidad de períodos/)).toBeFocused();
    await page.keyboard.type("10");
    await page.keyboard.press("Tab");
    await expect(dialog(page).getByLabel(/^Primer día del primer período/)).toBeFocused();
    await expectVisibleFocus(page);
    // Day and month are the same digits: the order of the segments of a date field follows the browser, not the page locale.
    await page.keyboard.type("05052026");
    await expect(dialog(page).getByLabel(/^Primer día del primer período/)).toHaveValue("2026-05-05");
    // The fields of a date are three stops and the button of its calendar a fourth, which the browser draws its own way
    // (it does not answer `:focus`), so from here only that the focus stays inside the dialog is checked until the buttons.
    const submit = dialog(page).getByRole("button", { name: "Agregar concepto" });
    for (let presses = 0; presses < 20 && !(await submit.evaluate((element) => element === document.activeElement)); presses += 1) {
      await page.keyboard.press("Tab");
      expect(await dialog(page).evaluate((element) => element.contains(document.activeElement)), `focus after ${String(presses + 1)} Tab`).toBe(true);
    }
    await expect(submit).toBeFocused();
    await expectVisibleFocus(page);

    await page.keyboard.press("Enter");

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(page.getByRole("table", { name: "Conceptos del plan Secundaria 2026" })).toBeVisible();
    await expect(opener).toBeFocused();
  });

  test("Escape closes the dialog and returns the focus to the opener; the confirmation of the activation works with Space and Escape keeps the plan a draft", async ({ page, scenarioId }) => {
    await openPlanDetail(page, scenarioId, SEEDED_PLANS.draftWithItem, "Segundo grado 2026");
    const activate = page.getByRole("button", { name: "Activar plan Segundo grado 2026" });
    await page.locator("body").press("Tab");
    await tabTo(page, activate);
    await page.keyboard.press("Space");
    await expect(dialog(page)).toBeVisible();
    await expect(dialog(page)).toContainText("queda congelado");

    await page.keyboard.press("Escape");

    await expect(dialog(page)).toBeHidden();
    await expect(activate).toBeFocused();
    await expectVisibleFocus(page);
    await page.keyboard.press("Space");
    await expect(dialog(page)).toBeVisible();
    await tabTo(page, dialog(page).getByRole("button", { name: "Activar plan" }));
    await page.keyboard.press("Space");
    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(page.getByText("Se activó el plan «Segundo grado 2026».")).toBeVisible();
  });
});
