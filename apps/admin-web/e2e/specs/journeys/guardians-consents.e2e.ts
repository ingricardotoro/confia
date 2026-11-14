import type { Locator, Page } from "@playwright/test";
import { expect } from "../../support/scenario.ts";
import { dialog, openGuardians, SAVE_TIMEOUT, test } from "../../support/guardians.ts";

// The consents section of the detail of a guardian (guardians-consent cut): the matrix with nothing preselected, the acceptance
// criterion (register the institutional consent by email with its policy version and the signed paper form, withdraw it, and
// the history keeps both), the rules the server states in words, and the keyboard. Real Chromium, the production build and the
// SIMULATED API: a simulation of the published shapes, not a test of the real stack or of its rules.
const section = (page: Page): Locator => dialog(page).getByRole("region", { name: "Consentimientos" });
const grantButton = (page: Page, names: string): Locator => section(page).getByRole("button", { name: `Registrar consentimiento: ${names}` });
const withdrawButton = (page: Page, names: string): Locator => section(page).getByRole("button", { name: `Registrar revocación: ${names}` });
const submitGrant = (page: Page): Locator => section(page).getByRole("button", { name: "Registrar consentimiento", exact: true });
const submitWithdraw = (page: Page): Locator => section(page).getByRole("button", { name: "Registrar revocación", exact: true });
const EMAIL_INSTITUTIONAL = "Correo electrónico, Institucional";

async function openConsents(page: Page, scenarioId: string, guardian: string): Promise<void> {
  await openGuardians(page, scenarioId);
  await page.getByRole("button", { name: `Ver detalle de ${guardian}` }).click();
  await expect(section(page).getByRole("table", { name: "Estado vigente del consentimiento por canal y finalidad" })).toBeVisible();
}

async function fillGrant(page: Page, version: string): Promise<void> {
  await section(page).getByRole("textbox", { name: /Versión de la política/ }).fill(version);
  await section(page).getByRole("combobox", { name: /Evidencia/ }).selectOption({ label: "Formulario en papel firmado" });
}

test.describe("the consents of a guardian", () => {
  test("shows the six combinations as never granted: nothing is preselected and there is no checkbox", async ({ page, scenarioId }) => {
    await openConsents(page, scenarioId, "Luis Alvarado");

    await expect(section(page).getByText("Sin consentimiento registrado")).toHaveCount(6);
    await expect(section(page).getByRole("checkbox")).toHaveCount(0);
    await expect(section(page).getByText("Todavía no hay eventos de consentimiento para este encargado.")).toBeVisible();
  });

  test("registers the institutional consent by email with the paper form, withdraws it, and the history keeps both events", async ({ page, scenarioId }) => {
    await openConsents(page, scenarioId, "Luis Alvarado");
    const opener = grantButton(page, EMAIL_INSTITUTIONAL);

    await opener.click();
    await expect(section(page).getByRole("textbox", { name: /Versión de la política/ })).toBeFocused();
    await fillGrant(page, "2026-01");
    await submitGrant(page).click();

    await expect(section(page).getByText("Se registró el consentimiento de Correo electrónico para la finalidad Institucional.")).toBeVisible(SAVE_TIMEOUT);
    await expect(section(page).getByText("Concedido")).toBeVisible();
    await expect(section(page).getByText("Política 2026-01", { exact: true })).toBeVisible();
    await expect(opener).toBeFocused();

    await withdrawButton(page, EMAIL_INSTITUTIONAL).click();
    await section(page).getByRole("combobox", { name: /Evidencia/ }).selectOption({ label: "Registrado por el personal" });
    await submitWithdraw(page).click();

    await expect(section(page).getByText("Se registró la revocación de Correo electrónico para la finalidad Institucional.")).toBeVisible(SAVE_TIMEOUT);
    await expect(section(page).getByText("Revocado")).toBeVisible();
    const history = section(page).getByRole("table", { name: "Eventos de consentimiento, del más reciente al más antiguo" });
    const rows = history.getByRole("row");
    await expect(rows).toHaveCount(3);
    await expect(rows.nth(1)).toContainText("Revocación");
    await expect(rows.nth(2)).toContainText("Concesión");
    await expect(section(page).getByRole("checkbox")).toHaveCount(0);
  });

  test("repeating the current state says nothing changed, and a new policy version over a grant is a change", async ({ page, scenarioId }) => {
    await openConsents(page, scenarioId, "Luis Alvarado");
    await grantButton(page, EMAIL_INSTITUTIONAL).click();
    await fillGrant(page, "2026-01");
    await submitGrant(page).click();
    await expect(section(page).getByText(/^Se registró el consentimiento/)).toBeVisible(SAVE_TIMEOUT);

    await grantButton(page, EMAIL_INSTITUTIONAL).click();
    await fillGrant(page, "2026-01");
    await submitGrant(page).click();
    await expect(section(page).getByText(/^Sin cambios: el consentimiento de Correo electrónico/)).toBeVisible(SAVE_TIMEOUT);

    await grantButton(page, EMAIL_INSTITUTIONAL).click();
    await fillGrant(page, "2026-02");
    await submitGrant(page).click();
    await expect(section(page).getByText(/^Se registró el consentimiento/)).toBeVisible(SAVE_TIMEOUT);
    await expect(section(page).getByText("Política 2026-02", { exact: true })).toBeVisible();
  });

  test("an empty form is refused before sending, with the summary focused", async ({ page, scenarioId }) => {
    await openConsents(page, scenarioId, "Luis Alvarado");

    await grantButton(page, "Correo electrónico, Mercadeo").click();
    await submitGrant(page).click();

    await expect(section(page).getByRole("group", { name: "Hay 2 campos con errores." })).toBeFocused();
    await expect(section(page).getByText("Este dato es obligatorio.")).toHaveCount(4);
  });

  test("a channel without data is refused in words and the form keeps what was typed", async ({ page, scenarioId }) => {
    await openConsents(page, scenarioId, "Rosa Aguilar");

    await grantButton(page, EMAIL_INSTITUTIONAL).click();
    await fillGrant(page, "2026-01");
    await submitGrant(page).click();

    const alert = section(page).getByRole("alert");
    await expect(alert).toContainText("no tiene registrado el dato de este canal", SAVE_TIMEOUT);
    await expect(alert).toBeFocused();
    await expect(section(page).getByRole("textbox", { name: /Versión de la política/ })).toHaveValue("2026-01");
  });

  test("an inactive guardian cannot be granted a consent, but a withdrawal is recorded", async ({ page, scenarioId }) => {
    await openConsents(page, scenarioId, "Mario Girón");
    await expect(section(page).getByText(/no se puede registrar un consentimiento nuevo, pero sí una revocación/)).toBeVisible();

    await grantButton(page, EMAIL_INSTITUTIONAL).click();
    await fillGrant(page, "2026-01");
    await submitGrant(page).click();
    await expect(section(page).getByRole("alert")).toContainText("está inactivo", SAVE_TIMEOUT);

    await section(page).getByRole("button", { name: "Cancelar" }).click();
    await withdrawButton(page, EMAIL_INSTITUTIONAL).click();
    await section(page).getByRole("combobox", { name: /Evidencia/ }).selectOption({ label: "Registrado por el personal" });
    await submitWithdraw(page).click();
    await expect(section(page).getByText(/^Se registró la revocación/)).toBeVisible(SAVE_TIMEOUT);
  });

  test("works by keyboard: Enter opens the form on its first field, Tab reaches the buttons, and cancelling returns the focus", async ({ page, scenarioId }) => {
    await openConsents(page, scenarioId, "Luis Alvarado");
    const opener = withdrawButton(page, "WhatsApp, Mercadeo");
    await opener.focus();

    await page.keyboard.press("Enter");
    await expect(section(page).getByRole("combobox", { name: /Evidencia/ })).toBeFocused();
    await page.keyboard.press("Tab");
    await expect(submitWithdraw(page)).toBeFocused();
    await page.keyboard.press("Tab");
    await expect(section(page).getByRole("button", { name: "Cancelar" })).toBeFocused();
    await page.keyboard.press("Enter");

    await expect(section(page).getByRole("combobox")).toHaveCount(0);
    await expect(opener).toBeFocused();
  });
});
