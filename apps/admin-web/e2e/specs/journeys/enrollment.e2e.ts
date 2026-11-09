import type { Page, Request } from "@playwright/test";
import { accountOf } from "../../support/account.ts";
import { expect, MOCK_API_URL, recordedCalls, test } from "../../support/scenario.ts";
import { makeSentinel } from "../../support/sentinels.ts";

// The first step of the enrollment in Chromium against the production build and the SIMULATED API (SS78, SS82 in part). The
// simulator imitates the shapes the backend publishes; none of this is evidence about the real stack (SS123). The key comes from
// the local control of the simulator, never from the repository, and the password is a sentinel made at run time.
const ORIGIN = "http://localhost:5173";
const IDENTIFIER = "docente.prueba@example.test";
const ENROLL_PATH = "/api/v1/auth/mfa/enrollments";

/** Every request of the page, whatever its destination. */
function recordUrls(page: Page): URL[] {
  const urls: URL[] = [];
  page.on("request", (request: Request) => {
    urls.push(new URL(request.url()));
  });
  return urls;
}

async function preset(page: Page, scenarioId: string, name: string): Promise<void> {
  const reply = await page.request.post(`${MOCK_API_URL}/__control/scenarios/${scenarioId}`, { data: { preset: name } });
  expect(reply.status()).toBe(200);
}

/** Signs in with fictitious credentials; the preset sends the person to the enrollment. */
async function signIn(page: Page): Promise<void> {
  await page.goto("/iniciar-sesion");
  await page.getByRole("textbox", { name: /Correo electrónico/ }).fill(IDENTIFIER);
  await page.locator("#sign-in-password").fill(makeSentinel("password"));
  await page.getByRole("button", { name: "Iniciar sesión" }).click();
}

// The annotation reaches the report: every journey says that it ran against the simulation.
test.describe("the enrollment screen, against the simulated API", { annotation: { type: "api", description: "simulated; not the real stack" } }, () => {
  test("[SS78] copying the key leaves the secret without spaces on the clipboard and announces it, and the QR code asks for nothing more", async ({
    page,
    context,
    request,
    scenarioId,
  }) => {
    await context.grantPermissions(["clipboard-read", "clipboard-write"], { origin: ORIGIN });
    await preset(page, scenarioId, "enrollment-required");
    const account = await accountOf(request, scenarioId);
    const urls = recordUrls(page);

    await signIn(page);
    await expect(page.getByRole("heading", { level: 2, name: "Escanee el código QR" })).toBeFocused();
    await expect(page.getByRole("img", { name: "Código QR para su aplicación de autenticación" })).toBeVisible();
    await expect(page.getByText(account.secret.match(/.{1,4}/g)?.join(" ") ?? "")).toBeVisible();

    await page.getByRole("button", { name: "Copiar clave" }).click();
    await expect(page.getByRole("status").filter({ hasText: "Clave copiada." })).toBeVisible();
    expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(account.secret);

    // The drawing is made in the page: nothing is asked of any server for it, and nothing leaves the origin.
    const seen = urls.length;
    await page.waitForLoadState("networkidle");
    expect(urls).toHaveLength(seen);
    expect(urls.filter((url) => url.origin !== ORIGIN)).toEqual([]);
    expect(urls.filter((url) => url.pathname === ENROLL_PATH)).toHaveLength(1);
  });

  test("[SS88] an enrollment token that expired before the request ends at the sign in screen with its notice", async ({ page, request, scenarioId }) => {
    await preset(page, scenarioId, "enrollment-expired");

    await signIn(page);

    await expect(page).toHaveURL(`${ORIGIN}/iniciar-sesion`);
    await expect(page.getByText(/La inscripción se interrumpió\./)).toBeVisible();
    await expect(page.getByRole("img")).toHaveCount(0);
    const calls = await recordedCalls(request, scenarioId);
    expect(calls.filter((call) => call.path === ENROLL_PATH)).toHaveLength(1);
  });
});
