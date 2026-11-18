import type { Locator, Page } from "@playwright/test";
import { ACTIVE_PLAN_NAME, dialog, openAccrual, previewPeriod, progress, TERMINAL, test } from "../../support/charges-accrual.ts";
import { expect } from "../../support/scenario.ts";

// The generation of charges with the keyboard alone (docs/ui-ux/03 section 9): Tab, Shift+Tab, Enter, Space, Escape and typing are the
// only input after the page opens, the focus is always visible, the confirmation dialog keeps it inside, and it goes back to the control
// that opened the dialog. Chromium, the production build and the SIMULATED API.
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

test.describe("the generation with the keyboard only", () => {
  test("chooses the plan and the period by typing, asks for the preview with Enter, and every stop of the way shows its focus", async ({ page, scenarioId }) => {
    await openAccrual(page, scenarioId);
    const plan = page.getByLabel(/^Plan de cobro/);
    await page.locator("body").press("Tab");
    await tabTo(page, plan);

    // Typing the name of an option chooses it.
    await page.keyboard.type(ACTIVE_PLAN_NAME);
    await expect(plan).toHaveValue(/00000000-0000-4000-8000-0000000090/);
    await expect(page.getByLabel(/^Período/)).toBeEnabled({ timeout: 20_000 });
    await page.keyboard.press("Tab");
    await expect(page.getByLabel(/^Período/)).toBeFocused();
    await expectVisibleFocus(page);
    await page.keyboard.type("2026-05");
    await expect(page.getByLabel(/^Período/)).toHaveValue("2026-05-01");
    await page.keyboard.press("Tab");
    await expect(page.getByRole("button", { name: "Calcular vista previa" })).toBeFocused();
    await expectVisibleFocus(page);

    await page.keyboard.press("Enter");

    await expect(page.getByRole("region", { name: "Resumen de la vista previa" })).toBeVisible({ timeout: 20_000 });
  });

  test("opens the confirmation with Enter, keeps the focus inside it, closes it with Escape and returns the focus to the button that opened it", async ({ page, scenarioId }) => {
    await openAccrual(page, scenarioId);
    await previewPeriod(page);
    const opener = page.getByRole("button", { name: "Confirmar generación" });
    await tabTo(page, opener);

    await page.keyboard.press("Enter");

    await expect(dialog(page)).toBeVisible();
    for (let presses = 0; presses < 6; presses += 1) {
      await page.keyboard.press("Tab");
      await expect(dialog(page).locator(":focus")).toHaveCount(1);
    }
    await page.keyboard.press("Escape");
    await expect(dialog(page)).toBeHidden();
    await expect(opener).toBeFocused();
  });

  test("confirms with the keyboard and lands on the run, whose progress is announced in a live region", async ({ page, scenarioId }) => {
    test.setTimeout(60_000);
    await openAccrual(page, scenarioId);
    await previewPeriod(page);
    await tabTo(page, page.getByRole("button", { name: "Confirmar generación" }));
    await page.keyboard.press("Enter");
    await expect(dialog(page)).toBeVisible();
    await tabTo(page, dialog(page).getByRole("button", { name: "Generar cargos" }));

    await page.keyboard.press("Space");

    await expect(page).toHaveURL(/\/charges\/accrual\/runs\/[0-9a-f-]{36}$/);
    await expect(progress(page)).toContainText("La ejecución terminó", TERMINAL);
  });
});
