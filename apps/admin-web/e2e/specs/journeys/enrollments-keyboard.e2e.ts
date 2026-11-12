import type { Locator, Page } from "@playwright/test";
import { SECTION_IDS } from "../../mock-api/organization-seed.ts";
import { dialog, openEnrollmentsPage, SAVE_TIMEOUT, test } from "../../support/enrollments.ts";
import { expect } from "../../support/scenario.ts";

// Enrolling a student with the keyboard alone (docs/ui-ux/03 section 9): Tab, Shift+Tab, Enter, Space, Escape, the arrow keys
// and typing are the only input after the page opens, the focus is always visible, the dialog keeps it inside, and it goes back to
// the control that opened it. Chromium, the production build and the SIMULATED API.
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
    const inDateField = await page.evaluate(() => document.activeElement instanceof HTMLInputElement && document.activeElement.type === "date");
    if (!inDateField) await expectVisibleFocus(page);
  }
  throw new Error(`The focus did not reach the control in ${String(MAX_TABS)} presses of Tab.`);
}

test.describe("the enrollments with the keyboard only", () => {
  test("finds a student, chooses it with the arrows and Enter, picks a section, sets the date and sends, and the focus returns to the opener", async ({ page }) => {
    await openEnrollmentsPage(page);
    const opener = page.getByRole("button", { name: "Matricular", exact: true });
    // From here on the mouse is never used.
    await page.locator("body").press("Tab");
    await tabTo(page, opener);

    await page.keyboard.press("Enter");

    await expect(dialog(page)).toBeVisible();
    const box = dialog(page).getByRole("combobox", { name: /Buscar estudiante/ });
    await expect(box).toBeFocused();
    await expectVisibleFocus(page);
    await page.keyboard.type("Zelaya");
    const option = dialog(page).getByRole("option", { name: "Andrea Zelaya · E-000059" });
    await expect(option).toBeVisible();
    await expect(box).toHaveAttribute("aria-expanded", "true");
    await page.keyboard.press("ArrowDown");
    await expect(option).toHaveAttribute("aria-selected", "true");
    await expect(box).toHaveAttribute("aria-activedescendant", (await option.getAttribute("id")) ?? "");

    // Enter chooses; it does not send the form.
    await page.keyboard.press("Enter");

    await expect(dialog(page).getByText("Andrea Zelaya · E-000059")).toBeVisible();
    await expect(dialog(page).getByRole("button", { name: "Cambiar estudiante" })).toBeFocused();
    await expect(dialog(page)).toBeVisible();
    const section = dialog(page).getByLabel(/^Sección/);
    await tabTo(page, section);
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("ArrowDown");
    await expect(section).toHaveValue(SECTION_IDS.firstB);
    await page.keyboard.press("Tab");
    // The date is typed by segments (day, month, year in the Spanish order), which the field moves through by itself.
    await expect(dialog(page).getByLabel(/^Fecha de matrícula/)).toBeFocused();
    await page.keyboard.type("02032026");
    await expect(dialog(page).getByLabel(/^Fecha de matrícula/)).toHaveValue("2026-03-02");
    await tabTo(page, dialog(page).getByRole("button", { name: "Cancelar" }));
    await page.keyboard.press("Tab");
    await expect(dialog(page).getByRole("button", { name: "Matricular" })).toBeFocused();
    await expectVisibleFocus(page);

    await page.keyboard.press("Enter");

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(page.getByText("Se matriculó a «Andrea Zelaya».")).toBeVisible();
    await expect(opener).toBeFocused();
  });

  test("an empty form puts its errors in front of the person without leaving the keyboard", async ({ page }) => {
    await openEnrollmentsPage(page);
    await tabTo(page, page.getByRole("button", { name: "Matricular", exact: true }));
    await page.keyboard.press("Space");
    await expect(dialog(page)).toBeVisible();
    await tabTo(page, dialog(page).getByRole("button", { name: "Matricular" }));

    await page.keyboard.press("Space");

    await expect(dialog(page).getByRole("group", { name: "Hay 2 campos con errores." })).toBeVisible();
    await expectVisibleFocus(page);
    // The summary takes the focus, and its first link leads to the first field with an error: the search of the student.
    await page.keyboard.press("Tab");
    await page.keyboard.press("Enter");
    await expect(dialog(page).getByRole("combobox", { name: /Buscar estudiante/ })).toBeFocused();
  });

  test("keeps the focus inside the dialog while it is open, and Escape closes it and returns the focus to the opener", async ({ page }) => {
    await openEnrollmentsPage(page);
    const opener = page.getByRole("button", { name: "Matricular", exact: true });
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

  test("registers an exit from the keyboard and the focus lands on the detail of the row that lost its actions", async ({ page }) => {
    await openEnrollmentsPage(page);
    const exit = page.getByRole("button", { name: "Registrar salida de Ana Aguilar" });
    await tabTo(page, exit);
    await page.keyboard.press("Enter");
    await expect(dialog(page).getByLabel(/^Tipo de salida/)).toBeFocused();
    await page.keyboard.press("Tab");
    await expect(dialog(page).getByLabel(/^Fecha de salida/)).toBeFocused();
    await page.keyboard.type("01042026");
    await tabTo(page, dialog(page).getByLabel(/^Motivo/));
    await page.keyboard.type("Cambio de ciudad");
    await tabTo(page, dialog(page).getByRole("button", { name: "Registrar salida" }));

    await page.keyboard.press("Enter");

    await expect(dialog(page)).toBeHidden(SAVE_TIMEOUT);
    await expect(page.getByRole("button", { name: "Ver detalle de la matrícula de Ana Aguilar" })).toBeFocused();
  });
});
