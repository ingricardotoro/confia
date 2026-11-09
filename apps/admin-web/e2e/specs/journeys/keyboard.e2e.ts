import type { Locator, Page } from "@playwright/test";
import { accountOf, currentCode } from "../../support/account.ts";
import { expect, MOCK_API_URL, test } from "../../support/scenario.ts";
import { makeSentinel } from "../../support/sentinels.ts";

// The sign in with a TOTP code done with the keyboard alone (SS113, docs/ui-ux/03 section 9), in Chromium against
// the production build and the SIMULATED API. Tab, Shift+Tab, Enter, Space and typing are the only input; the
// mouse is never used after the page opens. This is a simulation of the published shapes, not the real stack.
const IDENTIFIER = "docente.prueba@example.test";

/** The element with the focus is on the screen, and unless it is the heading (which draws no ring by design) it draws an outline. */
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

/** Presses a key and checks the focus after it. */
async function press(page: Page, key: string): Promise<void> {
  await page.keyboard.press(key);
  await expectVisibleFocus(page);
}

test.describe("the sign in with a code, with the keyboard only, against the simulated API", () => {
  test("[SS113] reaches the signed in page with Tab, Shift+Tab, Enter, Space and typing, and the focus is always visible", async ({
    page,
    request,
    scenarioId,
  }) => {
    const reply = await page.request.post(`${MOCK_API_URL}/__control/scenarios/${scenarioId}`, { data: { preset: "second-factor-required" } });
    expect(reply.status()).toBe(200);
    const account = await accountOf(request, scenarioId);
    const password = makeSentinel("password");

    await page.goto("/iniciar-sesion");
    // Arrival: the heading holds the focus; the first Tab goes to the first field, in the visual order.
    await expect(page.getByRole("heading", { level: 1, name: "Iniciar sesión" })).toBeFocused();
    await press(page, "Tab");
    await expect(page.getByRole("textbox", { name: /Correo electrónico/ })).toBeFocused();
    await page.keyboard.type(IDENTIFIER);
    await press(page, "Tab");
    await expect(page.locator("#sign-in-password")).toBeFocused();
    await page.keyboard.type(password);
    // The toggle of the password: Space turns it on, and Shift+Tab goes back to the field without losing the value.
    await press(page, "Tab");
    const toggle = page.getByRole("button", { name: "Mostrar contraseña" });
    await expect(toggle).toBeFocused();
    await press(page, "Space");
    await expect(toggle).toHaveAttribute("aria-pressed", "true");
    await press(page, "Shift+Tab");
    await expect(page.locator("#sign-in-password")).toHaveValue(password);
    await press(page, "Tab");
    await press(page, "Tab");
    await expect(page.getByRole("button", { name: "Iniciar sesión" })).toBeFocused();
    await page.keyboard.press("Enter");

    // The verification: the heading again holds the focus, then the code field, then the buttons in visual order.
    await expect(page.getByRole("heading", { level: 1, name: "Verificación en dos pasos" })).toBeFocused();
    await press(page, "Tab");
    await expect(page.getByRole("textbox", { name: /Código de verificación/ })).toBeFocused();
    await press(page, "Tab");
    await expect(page.getByRole("button", { name: "Verificar" })).toBeFocused();
    await press(page, "Tab");
    await expect(page.getByRole("button", { name: "Usar un código de recuperación" })).toBeFocused();
    await press(page, "Shift+Tab");
    await press(page, "Shift+Tab");
    await expect(page.getByRole("textbox", { name: /Código de verificación/ })).toBeFocused();
    // The code is computed right before it is sent, so a 30 s step boundary cannot fall between the two.
    await page.keyboard.type(currentCode(account));
    await page.keyboard.press("Enter");

    await expect(page.getByRole("heading", { level: 1, name: "Sesión iniciada." })).toBeVisible();
  });
});
