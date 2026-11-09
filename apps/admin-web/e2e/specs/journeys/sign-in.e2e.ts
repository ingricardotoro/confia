import { randomUUID } from "node:crypto";
import type { APIRequestContext, Page } from "@playwright/test";
import { expect, MOCK_API_URL, test } from "../../support/scenario.ts";

// The sign in screen in Chromium against the production build and the simulated API (SS48, SS50, SS102, AW09).
// The simulator is a simulation of the published shapes, not a test of the real stack. Passwords are made at run time.
const ORIGIN = "http://localhost:5173";
const IDENTIFIER = "docente.prueba@example.test";

async function applyPreset(request: APIRequestContext, scenarioId: string, preset: string): Promise<void> {
  const reply = await request.post(`${MOCK_API_URL}/__control/scenarios/${scenarioId}`, { data: { preset } });
  expect(reply.status()).toBe(200);
}

async function submitCredentials(page: Page): Promise<void> {
  await page.goto("/iniciar-sesion");
  await page.getByRole("textbox", { name: /Correo electrónico/ }).fill(IDENTIFIER);
  await page.locator("#sign-in-password").fill(randomUUID());
  await page.getByRole("button", { name: "Iniciar sesión" }).click();
}

test.describe("the sign in screen", () => {
  test("[SS102, AW09] an account without a second factor signs in to the signed in page, and no request leaves the origin of the page", async ({ page }) => {
    const sent: { url: string; method: string }[] = [];
    page.on("request", (request) => {
      sent.push({ url: request.url(), method: request.method() });
    });

    await submitCredentials(page);

    await expect(page.getByRole("heading", { level: 1, name: "Sesión iniciada." })).toBeVisible();
    await expect(page).toHaveURL(`${ORIGIN}/`);
    const network = sent.filter((request) => /^https?:/.test(request.url));
    expect(network.some((request) => request.url.endsWith("/api/v1/auth/sessions") && request.method === "POST")).toBe(true);
    expect(network.filter((request) => new URL(request.url).origin !== ORIGIN)).toEqual([]);
    expect(network.filter((request) => request.method === "OPTIONS")).toEqual([]);
  });

  test("[SS48] an answer that takes 31 seconds keeps the button busy, says that it is verifying, and ends with its result and no timeout error", async ({
    page,
    request,
    scenarioId,
  }) => {
    test.setTimeout(60_000);
    await applyPreset(request, scenarioId, "slow");

    await submitCredentials(page);

    const button = page.locator("button[type=submit]");
    const region = page.locator("[aria-live=polite]").filter({ hasText: "Verificando sus datos. Esto puede tardar hasta 30 segundos." });
    await expect(region).toBeVisible();
    // Still busy at three moments of the wait, never released early and never replaced by an error.
    for (const waitMs of [1_000, 14_000, 14_000]) {
      await page.waitForTimeout(waitMs);
      await expect(button).toHaveAttribute("aria-busy", "true");
      await expect(region).toBeVisible();
      await expect(page.getByRole("alert")).toHaveCount(0);
    }

    await expect(page.getByRole("heading", { level: 1, name: "Sesión iniciada." })).toBeVisible({ timeout: 20_000 });
  });

  test("[SS50, AW54] an unexpected error shows the generic message with the trace id and the control that copies it, and the alert has the focus", async ({
    page,
    request,
    scenarioId,
    context,
  }) => {
    await context.grantPermissions(["clipboard-read", "clipboard-write"], { origin: ORIGIN });
    await applyPreset(request, scenarioId, "internal-error");

    await submitCredentials(page);

    const alert = page.getByRole("alert");
    await expect(alert).toContainText("Ocurrió un error inesperado y la operación no se completó.");
    await expect(alert).toContainText("01J8Z4K2QF9P0X");
    await expect(alert).toBeFocused();
    await alert.getByRole("button", { name: "Copiar identificador" }).click();
    await expect(page.getByText("Identificador copiado.")).toBeVisible();
    expect(await page.evaluate(() => navigator.clipboard.readText())).toBe("01J8Z4K2QF9P0X");
  });
});
