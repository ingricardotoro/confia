import type { Page, Response } from "@playwright/test";
import { buildOtpauthUri } from "../../../src/features/identity/enrollment/otpauth-uri.ts";
import { problem } from "../../../src/test/contract-responses.ts";
import { accountOf, currentCode, type TestAccount } from "../../support/account.ts";
import { expect, MOCK_API_URL, recordedCalls, test } from "../../support/scenario.ts";
import { findLeaks, findTokenLeaks, makeSentinel, variantsOf } from "../../support/sentinels.ts";

// The seven journeys of access, each one with sentinels of its own, in Chromium against the production build and the SIMULATED API (SS01,
// SS02, SS04, SS05, SS116, SS117, I305). After every screen of every journey the page is searched for what must never be kept: the
// password, both tokens, the key, the URI and the ten recovery codes, in the forms a browser keeps them (variantsOf), in
// localStorage, sessionStorage, IndexedDB, the URL, history.state, the enumerable properties of window, the cookies visible to script
// and the console. The caches of Query are searched in jsdom (session-secrets.flow.test.tsx), because production does not expose the
// client (SS03). The simulator imitates the shapes the backend publishes; none of this is evidence about the real stack (SS123, I307).
// Every value is made at run time or handed out by the simulator, and a failure names the label of what leaked, never the value.
const ORIGIN = "http://localhost:5173";
const IDENTIFIER = "docente.prueba@example.test";
const SESSIONS = "/api/v1/auth/sessions";
const REFRESH = `${SESSIONS}/refresh`;
const CSRF_COOKIE = "__Host-confia_csrf";
const SIGNED_IN = { level: 1, name: "Sesión iniciada." } as const;
const VERIFICATION = { level: 1, name: "Verificación en dos pasos" } as const;
const QR_NAME = "Código QR para su aplicación de autenticación";

interface Outgoing {
  /** The request that carries the password, and the only one that may. */
  readonly signIn: boolean;
  readonly text: string;
}

/**
 * What a journey has seen: the values to search for, the console, every request that left, and the answers still being read. The
 * values come in three sets that never share a label: `known` (typed or read from the account control), `learned` (read by the
 * listener from the answers of the API) and `typed` (the six digit codes, which are searched as whole tokens).
 */
interface Watch {
  readonly known: Record<string, string>;
  readonly learned: Record<string, string>;
  readonly typed: Record<string, string>;
  readonly console: string[];
  readonly outgoing: Outgoing[];
  readonly reading: Promise<void>[];
}

/** Adds a value under a label that is not taken yet, so a token that rotates is searched as well as the first one. */
function remember(known: Record<string, string>, label: string, value: string): void {
  known[`${label} ${String(Object.keys(known).filter((taken) => taken.startsWith(label)).length)}`] = value;
}

/** Reads the answers of the API for the tokens, the key and the codes the screens receive. They are values of this run only. */
async function learn(learned: Record<string, string>, response: Response): Promise<void> {
  const body: unknown = await response.json().catch(() => null);
  if (typeof body !== "object" || body === null) return;
  const record = body as Record<string, unknown>;
  for (const [field, label] of [["accessToken", "access token"], ["secondFactorToken", "restricted token"], ["secret", "secret"]] as const) {
    if (typeof record[field] === "string") remember(learned, label, record[field]);
  }
  if (Array.isArray(record["recoveryCodes"])) {
    for (const code of record["recoveryCodes"]) if (typeof code === "string") remember(learned, "recovery code", code);
  }
}

function watch(page: Page, known: Record<string, string>): Watch {
  const seen: Watch = { known, learned: {}, typed: {}, console: [], outgoing: [], reading: [] };
  page.on("console", (message) => seen.console.push(`${message.type()}: ${message.text()}`));
  page.on("pageerror", (error) => seen.console.push(`pageerror: ${error.message}`));
  page.on("request", (request) => {
    seen.outgoing.push({
      signIn: request.method() === "POST" && new URL(request.url()).pathname === SESSIONS,
      text: `${request.url()}\n${JSON.stringify(request.headers())}\n${request.postData() ?? ""}`,
    });
  });
  page.on("response", (response) => {
    if (new URL(response.url()).pathname.startsWith("/api/")) seen.reading.push(learn(seen.learned, response));
  });
  return seen;
}

const groups = (secret: string): string => secret.match(/.{1,4}/g)?.join(" ") ?? "";

/** Every value, as typed and in the usual encodings; a secret also in groups and as the URI of its QR code. */
function searched(known: Readonly<Record<string, string>>): Record<string, string> {
  const everything: Record<string, string> = {};
  for (const [label, value] of Object.entries(known)) {
    Object.assign(everything, variantsOf(label, value));
    if (label.startsWith("secret")) {
      Object.assign(everything, variantsOf(`${label} in groups`, groups(value)), variantsOf(`${label} as URI`, buildOtpauthUri(IDENTIFIER, value)));
    }
  }
  return everything;
}

interface Surfaces {
  readonly localStorage: string;
  readonly sessionStorage: string;
  readonly indexedDB: string;
  readonly url: string;
  readonly historyState: string;
  readonly window: string;
  readonly cookies: string;
}

/** Everything of the page that outlives a render, as text: the places SS01, SS02 and SS04 name. */
function surfacesOf(page: Page): Promise<Surfaces> {
  return page.evaluate(async (): Promise<Surfaces> => {
    const dump = (storage: Storage) => JSON.stringify(Object.fromEntries(Object.keys(storage).map((key) => [key, storage.getItem(key)])));
    const wait = <T>(request: IDBRequest<T>) =>
      new Promise<T>((resolve, reject) => {
        request.onsuccess = () => {
          resolve(request.result);
        };
        request.onerror = () => {
          reject(request.error ?? new Error("indexedDB request failed"));
        };
      });
    const databases: unknown[] = [];
    for (const { name } of await indexedDB.databases()) {
      if (name === undefined) continue;
      const database = await wait(indexedDB.open(name));
      for (const store of Array.from(database.objectStoreNames)) {
        databases.push({ name, store, rows: await wait(database.transaction(store).objectStore(store).getAll()) });
      }
      database.close();
    }
    const properties = Object.keys(window).map((key) => {
      try {
        return `${key}=${JSON.stringify((window as unknown as Record<string, unknown>)[key])}`;
      } catch {
        return key;
      }
    });
    return {
      localStorage: dump(localStorage),
      sessionStorage: dump(sessionStorage),
      indexedDB: JSON.stringify(databases),
      url: location.href,
      historyState: JSON.stringify(history.state),
      window: properties.join("\n"),
      cookies: document.cookie,
    };
  });
}

/** The cookies a script can read, by name. */
const cookieNames = (surfaces: Surfaces): string[] =>
  surfaces.cookies
    .split("; ")
    .filter((cookie) => cookie !== "")
    .map((cookie) => cookie.split("=")[0] ?? "");

/** After a screen: no value in any surface or in the console; no key or URI in any request; the password only in the sign in; one visible cookie at most. */
async function expectClean(page: Page, seen: Watch, stage: string): Promise<Surfaces> {
  await Promise.all(seen.reading);
  const values = searched({ ...seen.known, ...seen.learned });
  const codes: Record<string, string> = {};
  for (const [label, value] of Object.entries(seen.typed)) Object.assign(codes, variantsOf(label, value));
  const surfaces = await surfacesOf(page);
  for (const [surface, text] of Object.entries({ ...surfaces, console: seen.console.join("\n") })) {
    expect(findLeaks(text, values), `${stage}: ${surface}`).toEqual([]);
    // A six digit code is searched as a whole token: inside a longer number it would match by chance (review R3-002).
    expect(findTokenLeaks(text, codes), `${stage}: ${surface}, the typed code`).toEqual([]);
  }
  const only = (prefix: string) => Object.fromEntries(Object.entries(values).filter(([label]) => label.startsWith(prefix)));
  expect(findLeaks(seen.outgoing.map((request) => request.text).join("\n"), only("secret")), `${stage}: the key or the URI in a request`).toEqual([]);
  expect(findLeaks(seen.outgoing.filter((request) => !request.signIn).map((request) => request.text).join("\n"), only("password")), `${stage}: the password outside the sign in`).toEqual([]);
  expect(cookieNames(surfaces).filter((name) => name !== CSRF_COOKIE), `${stage}: cookies visible to script`).toEqual([]);
  return surfaces;
}

async function preset(page: Page, scenarioId: string, name: string): Promise<void> {
  const reply = await page.request.post(`${MOCK_API_URL}/__control/scenarios/${scenarioId}`, { data: { preset: name } });
  expect(reply.status()).toBe(200);
}

/** What the account of the simulator holds: the key an authenticator would have and its recovery codes. */
function accountValues(account: TestAccount): Record<string, string> {
  const known: Record<string, string> = {};
  remember(known, "secret", account.secret);
  for (const code of account.recoveryCodes) remember(known, "recovery code", code);
  return known;
}

async function signIn(page: Page, password: string): Promise<void> {
  await page.goto("/iniciar-sesion");
  await page.getByRole("textbox", { name: /Correo electrónico/ }).fill(IDENTIFIER);
  await page.locator("#sign-in-password").fill(password);
  await page.getByRole("button", { name: "Iniciar sesión" }).click();
}

/** The sign in of an account without a second factor, up to the signed in page. */
async function signInToHome(page: Page, seen: Watch, password: string): Promise<void> {
  await signIn(page, password);
  await expect(page.getByRole("heading", SIGNED_IN)).toBeVisible();
  const names = cookieNames(await expectClean(page, seen, "the signed in page"));
  // SS05: the only cookie a script can read is the CSRF one; the app writes none (the session cookie is HttpOnly and the server's).
  expect(names).toEqual([CSRF_COOKIE]);
}

// The annotation reaches the report: every journey says that it ran against the simulation.
test.describe("the journeys of access and their secrets, against the simulated API", { annotation: { type: "api", description: "simulated; not the real stack" } }, () => {
  test("[SS01, SS02, SS04, SS05] the probe sees a value planted in each place it searches, so a clean result is not an empty one", async ({ page }) => {
    const planted = makeSentinel("planted");
    const seen = watch(page, { planted });
    await page.goto("/iniciar-sesion");

    await page.evaluate(async (value) => {
      localStorage.setItem("planted", value);
      sessionStorage.setItem("planted", value);
      (window as unknown as Record<string, unknown>)["planted"] = value;
      document.cookie = `planted=${value}`;
      console.log(value);
      await new Promise<void>((resolve, reject) => {
        const opened = indexedDB.open("planted");
        opened.onupgradeneeded = () => {
          opened.result.createObjectStore("rows");
        };
        opened.onerror = () => {
          reject(opened.error ?? new Error("indexedDB open failed"));
        };
        opened.onsuccess = () => {
          const transaction = opened.result.transaction("rows", "readwrite");
          transaction.objectStore("rows").put(value, "planted");
          transaction.oncomplete = () => {
            opened.result.close();
            resolve();
          };
        };
      });
      history.replaceState({ planted: value }, "", `/iniciar-sesion?planted=${encodeURIComponent(value)}`);
    }, planted);
    await expect.poll(() => seen.console.join("\n")).toContain(planted);

    const surfaces = await surfacesOf(page);
    for (const [surface, text] of Object.entries({ ...surfaces, console: seen.console.join("\n") })) {
      expect(findLeaks(text, { planted }), surface).toEqual(["planted"]);
    }
    expect(cookieNames(surfaces)).toContain("planted");
  });

  test("[SS116, SS117, I305] sign in with a TOTP code: no secret stays behind on any screen", async ({ page, request, scenarioId }) => {
    await preset(page, scenarioId, "second-factor-required");
    const account = await accountOf(request, scenarioId);
    const password = makeSentinel("password");
    const seen = watch(page, { password, ...accountValues(account) });

    await signIn(page, password);
    await expect(page.getByRole("heading", VERIFICATION)).toBeVisible();
    await expectClean(page, seen, "the verification");
    // The code is computed right before it is typed, so a 30 s step boundary cannot fall between the two.
    const code = currentCode(account);
    seen.typed["typed code"] = code;
    await page.getByRole("textbox", { name: /Código de verificación/ }).fill(code);
    await page.getByRole("button", { name: "Verificar" }).click();

    await expect(page.getByRole("heading", SIGNED_IN)).toBeVisible();
    expect(cookieNames(await expectClean(page, seen, "the signed in page"))).toEqual([CSRF_COOKIE]);
  });

  test("[SS116, SS117, I305] sign in with a recovery code: no secret stays behind on any screen", async ({ page, request, scenarioId }) => {
    await preset(page, scenarioId, "second-factor-required");
    const account = await accountOf(request, scenarioId);
    const password = makeSentinel("password");
    const seen = watch(page, { password, ...accountValues(account) });

    await signIn(page, password);
    await expect(page.getByRole("heading", VERIFICATION)).toBeVisible();
    await page.getByRole("button", { name: "Usar un código de recuperación" }).click();
    await expectClean(page, seen, "the recovery code field");
    await page.getByRole("textbox", { name: /Código de recuperación/ }).fill(account.recoveryCodes[0] ?? "");
    await page.getByRole("button", { name: "Verificar" }).click();

    await expect(page.getByRole("heading", SIGNED_IN)).toBeVisible();
    expect(cookieNames(await expectClean(page, seen, "the signed in page"))).toEqual([CSRF_COOKIE]);
  });

  test("[SS116, SS117, I305] sign in with the complete mandatory enrollment: no secret stays behind on any of its three steps", async ({
    page,
    request,
    scenarioId,
  }) => {
    await preset(page, scenarioId, "enrollment-required");
    const password = makeSentinel("password");
    const seen = watch(page, { password });

    await signIn(page, password);
    await expect(page.getByRole("img", { name: QR_NAME })).toBeVisible();
    // The simulator hands out a new secret at every enrollment, so the account is read once the screen has its one.
    const account = await accountOf(request, scenarioId);
    Object.assign(seen.known, accountValues(account));
    // What the screen received, read from the answer of the API by the listener and kept apart from what the account control handed out.
    await Promise.all(seen.reading);
    const received = (prefix: string) => Object.entries(seen.learned).filter(([label]) => label.startsWith(prefix)).map(([, value]) => value).sort();
    // Booleans, so a failure does not print the key or the codes.
    expect(received("secret").join() === account.secret, "the key of the enrollment answer was learned").toBe(true);
    expect(received("recovery code").join() === [...account.recoveryCodes].sort().join(), "the ten codes of the enrollment answer were learned").toBe(true);
    expect(account.recoveryCodes).toHaveLength(10);
    await expectClean(page, seen, "the secret");
    await page.getByRole("button", { name: "Continuar" }).click();
    await expect(page.getByRole("heading", { level: 2, name: "Guarde sus códigos de recuperación" })).toBeVisible();
    await expectClean(page, seen, "the recovery codes");
    await page.getByRole("checkbox", { name: /He guardado mis códigos de recuperación/ }).click();
    await page.getByRole("button", { name: "Continuar" }).click();
    await expect(page.getByRole("heading", { level: 2, name: "Escriba el primer código" })).toBeVisible();
    await expectClean(page, seen, "the first code");
    const code = currentCode(account);
    seen.typed["typed code"] = code;
    await page.getByRole("textbox", { name: /Código de verificación/ }).fill(code);
    await page.getByRole("button", { name: "Activar" }).click();

    await expect(page.getByRole("heading", SIGNED_IN)).toBeVisible();
    expect(cookieNames(await expectClean(page, seen, "the signed in page"))).toEqual([CSRF_COOKIE]);
    // The listener also read the access token of the activation, so the tokens were among the values searched on the last screen.
    expect(Object.keys(seen.learned).some((label) => label.startsWith("access token"))).toBe(true);
  });

  test("[SS116, SS117, I305] reload that keeps the session: the restored session leaves no secret either", async ({ page, request, scenarioId }) => {
    const password = makeSentinel("password");
    const seen = watch(page, { password });
    await signInToHome(page, seen, password);

    await page.reload();

    await expect(page.getByRole("heading", SIGNED_IN)).toBeVisible();
    expect(cookieNames(await expectClean(page, seen, "the reloaded page"))).toEqual([CSRF_COOKIE]);
    // Nothing of the session was in the page, so the reload restored it from the cookie with a refresh.
    expect((await recordedCalls(request, scenarioId)).filter((call) => call.path === REFRESH)).toHaveLength(1);
  });

  test("[SS116, SS117, I305] sign out: the sign in screen that follows leaves no secret either", async ({ page, scenarioId, request }) => {
    const password = makeSentinel("password");
    const seen = watch(page, { password });
    await signInToHome(page, seen, password);

    await page.getByRole("button", { name: "Cerrar sesión" }).click();

    await expect(page.getByRole("heading", { level: 1, name: "Iniciar sesión" })).toBeVisible();
    await expectClean(page, seen, "after the sign out");
    expect((await recordedCalls(request, scenarioId)).filter((call) => call.method === "DELETE" && call.path.startsWith(SESSIONS))).toHaveLength(1);
  });

  test("[SS116, SS117, I305] expired session: the notice that follows leaves no secret either", async ({ page }) => {
    const password = makeSentinel("password");
    const seen = watch(page, { password });
    await signInToHome(page, seen, password);
    const expired = problem("session-expired", 401);
    await page.route(`**${REFRESH}`, (route) =>
      route.fulfill({ status: expired.status, contentType: expired.contentType ?? "application/problem+json", body: JSON.stringify(expired.body) }),
    );

    await page.reload();

    await expect(page.getByText("Su sesión se cerró por seguridad. Vuelva a iniciar sesión.")).toBeVisible();
    await expectClean(page, seen, "the expired session");
  });

  test("[SS116, SS117, I305] expired restricted token: the sign in screen with its notice leaves no secret either", async ({ page, request, scenarioId }) => {
    await preset(page, scenarioId, "restricted-expired");
    const account = await accountOf(request, scenarioId);
    const password = makeSentinel("password");
    const seen = watch(page, { password, ...accountValues(account) });

    await signIn(page, password);
    await expect(page.getByRole("heading", VERIFICATION)).toBeVisible();
    await expectClean(page, seen, "the verification");
    const code = currentCode(account);
    seen.typed["typed code"] = code;
    await page.getByRole("textbox", { name: /Código de verificación/ }).fill(code);
    await page.getByRole("button", { name: "Verificar" }).click();

    await expect(page.getByText("La verificación se interrumpió. Inicie sesión de nuevo para continuar.")).toBeVisible();
    await expect(page).toHaveURL(`${ORIGIN}/iniciar-sesion`);
    await expectClean(page, seen, "the notice");
  });

  test("[SS02] going back and forward in the browser keeps every secret out of the URL and the history state", async ({ page, request, scenarioId }) => {
    await preset(page, scenarioId, "second-factor-required");
    const account = await accountOf(request, scenarioId);
    const password = makeSentinel("password");
    const seen = watch(page, { password, ...accountValues(account) });

    await signIn(page, password);
    await expect(page.getByRole("heading", VERIFICATION)).toBeVisible();
    await expectClean(page, seen, "the verification");
    expect(await page.evaluate(() => history.length)).toBeGreaterThan(1);

    await page.goBack();
    await expectClean(page, seen, "after going back");
    await page.goForward();
    await expectClean(page, seen, "after going forward");
  });
});
