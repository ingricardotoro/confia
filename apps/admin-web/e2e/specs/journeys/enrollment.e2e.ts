import type { Page, Request } from "@playwright/test";
import jsQR from "jsqr";
import { buildOtpauthUri } from "../../../src/features/identity/enrollment/otpauth-uri.ts";
import { accountOf, currentCode } from "../../support/account.ts";
import { expect, MOCK_API_URL, recordedCalls, test } from "../../support/scenario.ts";
import { findLeaks, makeSentinel } from "../../support/sentinels.ts";

// The enrollment in Chromium against the production build and the SIMULATED API (SS78, SS81, SS82, SS84, SS85, SS100). The
// simulator imitates the shapes the backend publishes; none of this is evidence about the real stack (SS123). The key comes from
// the local control of the simulator, never from the repository, and the password is a sentinel made at run time.
const ORIGIN = "http://localhost:5173";
const IDENTIFIER = "docente.prueba@example.test";
const ENROLL_PATH = "/api/v1/auth/mfa/enrollments";
const QR_NAME = "Código QR para su aplicación de autenticación";

/** The brightness of a computed color, `rgb(r, g, b)`: enough to say which of two colors is darker. */
function luminance(color: string): number {
  const [red = 0, green = 0, blue = 0] = (color.match(/\d+(?:\.\d+)?/g) ?? []).slice(0, 3).map(Number);
  return 0.2126 * red + 0.7152 * green + 0.0722 * blue;
}

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
    const urls = recordUrls(page);

    await signIn(page);
    await expect(page.getByRole("heading", { level: 2, name: "Escanee el código QR" })).toBeFocused();
    // The simulator hands out a new secret at every enrollment, so the account is read once the screen has its one.
    const account = await accountOf(request, scenarioId);
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

  test("[SS81] the QR code, drawn on a canvas of the page and decoded with jsqr, gives exactly the URI of the secret", async ({
    page,
    request,
    scenarioId,
  }) => {
    await preset(page, scenarioId, "enrollment-required");

    await signIn(page);
    await expect(page.getByRole("img", { name: QR_NAME })).toBeVisible();
    const account = await accountOf(request, scenarioId);
    const picture = await page.evaluate(async () => {
      const svg = document.querySelector<SVGSVGElement>("svg[role=img]");
      if (svg === null) throw new Error("no QR code in the page");
      const size = svg.viewBox.baseVal.width * 6;
      const image = new Image();
      image.src = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(new XMLSerializer().serializeToString(svg))}`;
      await image.decode();
      const canvas = document.createElement("canvas");
      canvas.width = size;
      canvas.height = size;
      const context = canvas.getContext("2d");
      if (context === null) throw new Error("no canvas context");
      context.drawImage(image, 0, 0, size, size);
      return { size, data: Array.from(context.getImageData(0, 0, size, size).data) };
    });

    const decoded = jsQR(Uint8ClampedArray.from(picture.data), picture.size, picture.size);

    // A boolean, so a failure does not print the secret that is in the URI.
    expect(decoded?.data === buildOtpauthUri(IDENTIFIER, account.secret), "the decoded text is the URI of the identifier and the secret").toBe(true);
  });

  test("[SS82] with everything blocked but the app and its API, the QR code is shown and nothing more is asked", async ({ page, scenarioId }) => {
    await preset(page, scenarioId, "enrollment-required");
    const blocked: string[] = [];
    await page.route(
      (url) => url.origin !== ORIGIN,
      (route) => {
        blocked.push(new URL(route.request().url()).origin);
        return route.abort("blockedbyclient");
      },
    );
    const urls = recordUrls(page);

    await signIn(page);
    await expect(page.getByRole("img", { name: QR_NAME })).toBeVisible();
    const seen = urls.length;
    await page.waitForLoadState("networkidle");

    expect(urls).toHaveLength(seen);
    expect(blocked).toEqual([]);
  });

  test("[SS84] with the dark preference, the modules are darker than the background and the background surrounds them", async ({
    page,
    scenarioId,
  }) => {
    await page.emulateMedia({ colorScheme: "dark" });
    await preset(page, scenarioId, "enrollment-required");

    await signIn(page);
    await expect(page.getByRole("img", { name: QR_NAME })).toBeVisible();
    const drawing = await page.evaluate(() => {
      const paths = document.querySelectorAll("svg[role=img] path");
      const [background, modules] = Array.from(paths);
      if (background === undefined || modules === undefined) throw new Error("the QR code has no background and modules");
      return {
        viewBox: document.querySelector("svg[role=img]")?.getAttribute("viewBox") ?? "",
        backgroundFill: getComputedStyle(background).fill,
        modulesFill: getComputedStyle(modules).fill,
        backgroundPath: background.getAttribute("d") ?? "",
        modulesPath: modules.getAttribute("d") ?? "",
      };
    });

    expect(luminance(drawing.modulesFill)).toBeLessThan(luminance(drawing.backgroundFill));
    // The background covers the whole drawing and the modules start inside it, leaving a quiet zone on every side.
    const side = Number(drawing.viewBox.split(" ")[2]);
    expect(drawing.backgroundPath).toMatch(new RegExp(`^M0,?0 ?h${String(side)}v${String(side)}H0z$`));
    const squares = [...drawing.modulesPath.matchAll(/M(\d+)[ ,](\d+)h(\d+)v1/g)].map((m) => ({ x: Number(m[1]), y: Number(m[2]), width: Number(m[3]) }));
    expect(squares.length).toBeGreaterThan(0);
    expect(Math.min(...squares.map((square) => Math.min(square.x, square.y)))).toBeGreaterThanOrEqual(4);
    expect(Math.max(...squares.map((square) => Math.max(square.x + square.width, square.y + 1)))).toBeLessThanOrEqual(side - 4);
  });

  test("[SS85] neither the secret nor the URI leaves the browser in any request of a whole enrollment", async ({ page, request, scenarioId }) => {
    await preset(page, scenarioId, "enrollment-required");
    const sent: string[] = [];
    page.on("request", (made: Request) => {
      sent.push(`${made.url()}\n${JSON.stringify(made.headers())}\n${made.postData() ?? ""}`);
    });

    await signIn(page);
    await expect(page.getByRole("img", { name: QR_NAME })).toBeVisible();
    const account = await accountOf(request, scenarioId);
    await page.getByRole("button", { name: "Continuar" }).click();
    await page.getByRole("checkbox", { name: /He guardado mis códigos de recuperación/ }).click();
    await page.getByRole("button", { name: "Continuar" }).click();
    await page.getByRole("textbox", { name: /Código de verificación/ }).fill(currentCode(account));
    await page.getByRole("button", { name: "Activar" }).click();
    await expect(page.getByRole("heading", { level: 1, name: "Sesión iniciada." })).toBeVisible();

    const everything = sent.join("\n");
    // Labels only: a failure names what leaked and never prints it.
    expect(
      findLeaks(everything, {
        secret: account.secret,
        "secret in groups": account.secret.match(/.{1,4}/g)?.join(" ") ?? "",
        "secret in groups, encoded": encodeURIComponent(account.secret.match(/.{1,4}/g)?.join(" ") ?? ""),
        "otpauth scheme": "otpauth",
        "URI label": "CONFIA:",
        "URI label, encoded": "CONFIA%3A",
        ...Object.fromEntries(account.recoveryCodes.map((code, index) => [`recovery code ${String(index)}`, code])),
      }),
    ).toEqual([]);
    // The identifier travels once, in the body of the sign in, and as nothing else.
    expect(sent.filter((entry) => entry.includes(IDENTIFIER)).length).toBe(1);
  });

  test("[SS100] restarting the enrollment after a reload shows a secret other than the earlier one", async ({ page, scenarioId }) => {
    await preset(page, scenarioId, "enrollment-required");
    const keyShown = () => page.locator("code").first().innerText();

    await signIn(page);
    await expect(page.getByRole("img", { name: QR_NAME })).toBeVisible();
    const earlier = await keyShown();
    await page.reload();

    await expect(page.getByText(/La inscripción se interrumpió\./)).toBeVisible();
    await signIn(page);
    await expect(page.getByRole("img", { name: QR_NAME })).toBeVisible();
    const later = await keyShown();

    expect(earlier).toMatch(/^[A-Z2-7]{4}( [A-Z2-7]{4}){7}$/);
    // A boolean, so a failure does not print the keys.
    expect(later !== earlier, "the second secret differs from the first").toBe(true);
  });
});
