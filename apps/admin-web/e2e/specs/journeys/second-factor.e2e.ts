import type { Page, Request } from "@playwright/test";
import { accountOf, currentCode } from "../../support/account.ts";
import { expect, MOCK_API_URL, recordedCalls, test } from "../../support/scenario.ts";
import { makeSentinel } from "../../support/sentinels.ts";

// The verification screen in Chromium against the production build and the SIMULATED API (SS115, I304). The
// simulator imitates the shapes the backend publishes; none of this is evidence about the real stack (SS123).
// The password of each journey is a sentinel made at run time, and the key and the codes come from the local
// control of the simulator, never from the repository.
const ORIGIN = "http://localhost:5173";
const IDENTIFIER = "docente.prueba@example.test";
const VERIFY_PATH = "/api/v1/auth/mfa/verifications";

interface Sent {
  readonly method: string;
  readonly path: string;
  readonly body: string | null;
}

/** Every request of the page to the API, with its body, as the browser sent it. */
function recordRequests(page: Page): Sent[] {
  const sent: Sent[] = [];
  page.on("request", (request: Request) => {
    const url = new URL(request.url());
    if (url.pathname.startsWith("/api/")) sent.push({ method: request.method(), path: url.pathname, body: request.postData() });
  });
  return sent;
}

const verifications = (sent: readonly Sent[]): unknown[] =>
  sent.filter((request) => request.method === "POST" && request.path === VERIFY_PATH).map((request) => JSON.parse(request.body ?? "null") as unknown);

async function preset(page: Page, scenarioId: string, name: string): Promise<void> {
  const reply = await page.request.post(`${MOCK_API_URL}/__control/scenarios/${scenarioId}`, { data: { preset: name } });
  expect(reply.status()).toBe(200);
}

/** Signs in with fictitious credentials and waits for the verification screen. */
async function reachVerification(page: Page, password: string): Promise<void> {
  await page.goto("/iniciar-sesion");
  await page.getByRole("textbox", { name: /Correo electrónico/ }).fill(IDENTIFIER);
  await page.locator("#sign-in-password").fill(password);
  await page.getByRole("button", { name: "Iniciar sesión" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Verificación en dos pasos" })).toBeVisible();
}

const authenticated = (page: Page) => page.getByRole("heading", { level: 1, name: "Sesión iniciada." });

// The annotation reaches the report: every journey says that it ran against the simulation.
test.describe("the verification screen, against the simulated API", { annotation: { type: "api", description: "simulated; not the real stack" } }, () => {
  test("[I304] a TOTP code computed from the account key completes the sign in, and only the code travels", async ({ page, request, scenarioId }) => {
    await preset(page, scenarioId, "second-factor-required");
    const account = await accountOf(request, scenarioId);
    const password = makeSentinel("password");
    const sent = recordRequests(page);

    await reachVerification(page, password);
    const code = currentCode(account);
    await page.getByRole("textbox", { name: /Código de verificación/ }).fill(code);
    await page.getByRole("button", { name: "Verificar" }).click();

    await expect(authenticated(page)).toBeVisible();
    await expect(page).toHaveURL(`${ORIGIN}/`);
    expect(verifications(sent)).toEqual([{ code }]);
    expect(sent.filter((request) => request.body?.includes(password) === true).map((request) => request.path)).toEqual(["/api/v1/auth/sessions"]);
  });

  test("[SS115] pasting 123 456 with the browser shortcut is accepted and 123456 is what is sent", async ({ page, context, scenarioId }) => {
    await context.grantPermissions(["clipboard-read", "clipboard-write"], { origin: ORIGIN });
    await preset(page, scenarioId, "second-factor-required");
    const sent = recordRequests(page);
    await reachVerification(page, makeSentinel("password"));
    await page.evaluate(() => navigator.clipboard.writeText("123 456"));

    const field = page.getByRole("textbox", { name: /Código de verificación/ });
    await field.focus();
    await page.keyboard.press("ControlOrMeta+V");
    await expect(field).not.toHaveValue("");
    await page.getByRole("button", { name: "Verificar" }).click();

    // The simulator refuses this code (it is not the current one), which is what lets the request be read.
    await expect(page.getByRole("alert")).toContainText("El código de verificación no es correcto o ya expiró.");
    expect(verifications(sent)).toEqual([{ code: "123456" }]);
  });

  test("a recovery code, in the case the simulator handed it out, is sent in capitals and completes the sign in", async ({ page, request, scenarioId }) => {
    await preset(page, scenarioId, "second-factor-required");
    const account = await accountOf(request, scenarioId);
    const recoveryCode = account.recoveryCodes[0] ?? "";
    const sent = recordRequests(page);

    await reachVerification(page, makeSentinel("password"));
    await page.getByRole("button", { name: "Usar un código de recuperación" }).click();
    const field = page.getByRole("textbox", { name: /Código de recuperación/ });
    await expect(field).toBeFocused();
    await field.fill(recoveryCode);
    await page.getByRole("button", { name: "Verificar" }).click();

    await expect(authenticated(page)).toBeVisible();
    expect(verifications(sent)).toEqual([{ recoveryCode: recoveryCode.toUpperCase() }]);
    // The code is spent: the account no longer lists it.
    expect((await accountOf(request, scenarioId)).recoveryCodes).not.toContain(recoveryCode);
  });

  test("an expired restricted token ends the verification at the sign in screen with its notice", async ({ page, request, scenarioId }) => {
    await preset(page, scenarioId, "restricted-expired");
    const account = await accountOf(request, scenarioId);
    await reachVerification(page, makeSentinel("password"));

    await page.getByRole("textbox", { name: /Código de verificación/ }).fill(currentCode(account));
    await page.getByRole("button", { name: "Verificar" }).click();

    await expect(page).toHaveURL(`${ORIGIN}/iniciar-sesion`);
    await expect(page.getByText("La verificación se interrumpió. Inicie sesión de nuevo para continuar.")).toBeVisible();
    await expect(page.getByRole("textbox", { name: /Código de verificación/ })).toHaveCount(0);
    const calls = await recordedCalls(request, scenarioId);
    expect(calls.filter((call) => call.path === VERIFY_PATH)).toHaveLength(1);
  });
});
