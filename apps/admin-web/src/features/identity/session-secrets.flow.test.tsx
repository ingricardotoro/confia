import { dehydrate, type QueryClient } from "@tanstack/react-query";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import * as api from "../../shared/api/auth-operations.ts";
import { authenticatedSession, currentSession, enrollment, problem, secondFactorEnrollmentRequired, secondFactorRequired } from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { renderApp } from "../../test/render-app.tsx";
import { createAccessSession } from "./session/access-session.ts";

// admin-web-foundations-and-session, task 9.1 (part 9b; SS06, and the screen clause of SS18, SS25 and SS26).
// The whole App is mounted, because production does not expose the QueryClient: both caches are read from
// the client the test gives it. Tokens and identifiers are made at run time (CLAUDE.md rule 13).
installMswServer();

const SESSIONS = "/api/v1/auth/sessions";
const REFRESH = `${SESSIONS}/refresh`;
const CURRENT = `${SESSIONS}/current`;
const EXPIRED = "Su sesión se cerró por seguridad. Vuelva a iniciar sesión.";

afterEach(() => {
  vi.restoreAllMocks();
});

describe("what the caches and the document hold", () => {
  // The first render of the whole app in a file builds the module graph cold: the waits are explicit.
  it("SS06: the query and mutation caches hold the metadata of the session and no token", { timeout: 20_000 }, async () => {
    const accessToken = `access-${crypto.randomUUID()}`;
    const identifiers = { accountId: crypto.randomUUID(), institutionId: crypto.randomUUID(), sessionId: crypto.randomUUID() };
    route("post", REFRESH, authenticatedSession({ override: { accessToken } }));
    route("get", CURRENT, currentSession({ override: identifiers }));

    const { queryClient } = renderApp({ path: "/", readCsrf: () => "csrf-value-A" });
    await screen.findByText("Su sesión está activa.", {}, { timeout: 10_000 });

    const held = JSON.stringify({
      queries: dehydrate(queryClient, { shouldDehydrateQuery: () => true }),
      mutations: dehydrate(queryClient, { shouldDehydrateMutation: () => true }),
    });
    expect(held).toContain(identifiers.accountId);
    expect(held).not.toContain(accessToken);
    expect(requests().find((sent) => sent.path === CURRENT)?.headers.get("Authorization")).toBe(`Bearer ${accessToken}`);
  });
});

describe("the screen clause of the session scenarios", () => {
  it("SS18: without Web Locks the tab restores with one request, no error and no polling interval", async () => {
    const interval = vi.spyOn(globalThis, "setInterval");
    route("post", REFRESH, authenticatedSession());
    route("get", CURRENT, currentSession());
    const session = createAccessSession({ api, locks: undefined, now: () => Date.now(), readCsrf: () => "csrf-value-A" });

    renderApp({ path: "/", session, readCsrf: () => "csrf-value-A" });

    expect(await screen.findByText("Su sesión está activa.")).toBeInTheDocument();
    expect(requests().filter((sent) => sent.path === REFRESH)).toHaveLength(1);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    // Testing Library polls with its own short interval while it waits; a polling refresh would be minutes.
    expect(interval.mock.calls.filter(([, delay]) => (delay ?? 0) >= 1_000)).toEqual([]);
  });

  it("SS25: a 401 to the read ends the session with no refresh and no second read, and the cache is empty", async () => {
    route("post", REFRESH, authenticatedSession());
    route("get", CURRENT, problem("token-expired", 401));

    const { queryClient } = renderApp({ path: "/", readCsrf: () => "csrf-value-A" });

    expect(await screen.findByText(EXPIRED)).toBeInTheDocument();
    expect(requests().filter((sent) => sent.path === REFRESH)).toHaveLength(1);
    expect(requests().filter((sent) => sent.path === CURRENT)).toHaveLength(1);
    expect(queryClient.getQueryCache().getAll()).toEqual([]);
  });

  it("SS26: a refresh that is not a 200 ends the session, empties the cache and is not tried a second time", async () => {
    // The first refresh restores a token with 59 seconds left, so the read must refresh once more.
    const shortLived = new Date(Date.now() + 59_000).toISOString();
    route("post", REFRESH, authenticatedSession({ override: { accessTokenExpiresAt: shortLived } }), problem("csrf-invalid", 403));
    route("get", CURRENT, currentSession());

    const { queryClient } = renderApp({ path: "/", readCsrf: () => "csrf-value-A" });

    expect(await screen.findByText(EXPIRED)).toBeInTheDocument();
    expect(requests().filter((sent) => sent.path === REFRESH)).toHaveLength(2);
    expect(requests().filter((sent) => sent.path === CURRENT)).toHaveLength(0);
    expect(queryClient.getQueryCache().getAll()).toEqual([]);
  });
});

// admin-web-staff-sign-in, task 9.1 (SS03 and the jsdom part of SS116). The same whole App, now through the three access screens
// with values made at run time for the password, both tokens, the code that is typed, the secret and the ten recovery codes. After
// every screen the whole content of both caches (data, variables and errors) and everything the console received is searched.
const LIMIT = { timeout: 30_000 };
const VERIFICATIONS = "/api/v1/auth/mfa/verifications";
const ENROLLMENTS = "/api/v1/auth/mfa/enrollments";
const WAIT = { timeout: 20_000 };

function describeError(error: unknown): unknown {
  return error instanceof Error ? `${error.name} ${error.message} ${error.stack ?? ""}` : error;
}

/** Everything both caches hold, as text: the dehydrated form and the raw state, which also has variables, context and errors. */
function held(client: QueryClient): string {
  return JSON.stringify({
    dehydratedQueries: dehydrate(client, { shouldDehydrateQuery: () => true }),
    dehydratedMutations: dehydrate(client, { shouldDehydrateMutation: () => true }),
    queries: client.getQueryCache().getAll().map((query) => ({ key: query.queryKey, data: query.state.data, error: describeError(query.state.error) })),
    mutations: client
      .getMutationCache()
      .getAll()
      .map((mutation) => ({ variables: mutation.state.variables, data: mutation.state.data, context: mutation.state.context, error: describeError(mutation.state.error) })),
  });
}

/** The labels of the values that appear in `text`; a failure names what leaked and never prints it (CLAUDE.md rule 11). */
function leaks(text: string, values: Readonly<Record<string, string>>): string[] {
  return Object.keys(values).filter((label) => text.includes(values[label] ?? ""));
}

function listenToConsole(): () => string {
  const spies = (["log", "info", "warn", "error", "debug"] as const).map((method) => vi.spyOn(console, method).mockImplementation(() => undefined));
  return () => JSON.stringify(spies.flatMap((spy) => spy.mock.calls.map((call) => call.map(describeError))));
}

const typedCode = () => String(100_000 + Math.floor(Math.random() * 900_000));
const identifierField = () => screen.getByRole("textbox", { name: /Correo electrónico/ });
const passwordField = () => screen.getByLabelText(/Contraseña/, { selector: "input" });

async function signInWith(user: ReturnType<typeof userEvent.setup>, password: string): Promise<void> {
  await user.type(identifierField(), "ana.pérez@colegio.edu.hn");
  await user.type(passwordField(), password);
  await user.click(screen.getByRole("button", { name: "Iniciar sesión" }));
}

describe("SS03, SS116: the caches and the console after each access screen", () => {
  it("the detector sees a value planted in a query, in the variables of a mutation and in the console", LIMIT, async () => {
    const planted = `planted-${crypto.randomUUID()}`;
    const { queryClient } = renderApp({ path: "/iniciar-sesion" });
    const consoleText = listenToConsole();
    queryClient.setQueryData(["planted"], { value: planted });
    await queryClient.getMutationCache().build(queryClient, { mutationFn: () => Promise.resolve(1) }).execute({ planted });
    console.warn(planted);

    expect(leaks(held(queryClient), { query: `"value":"${planted}"`, variables: `"variables":{"planted":"${planted}"}` })).toEqual(["query", "variables"]);
    expect(leaks(consoleText(), { console: planted })).toEqual(["console"]);
  });

  it("the sign in screen: credentials that are not accepted leave the password out of both caches and the console", LIMIT, async () => {
    const password = `password-${crypto.randomUUID()}`;
    route("post", SESSIONS, problem("authentication-failed", 401));
    const { queryClient } = renderApp({ path: "/iniciar-sesion" });
    const consoleText = listenToConsole();
    const user = userEvent.setup();

    await screen.findByRole("heading", { level: 1, name: "Iniciar sesión" }, WAIT);
    await signInWith(user, password);

    expect(await screen.findByText("El correo o la contraseña no son correctos.", {}, WAIT)).toBeInTheDocument();
    expect(leaks(held(queryClient), { password })).toEqual([]);
    expect(leaks(consoleText(), { password })).toEqual([]);
  });

  it("the verification screen: neither the restricted token, the password, the code nor the access token is held", LIMIT, async () => {
    const values = { password: `password-${crypto.randomUUID()}`, restrictedToken: `restricted-${crypto.randomUUID()}`, accessToken: `access-${crypto.randomUUID()}` };
    const code = typedCode();
    route("post", SESSIONS, secondFactorRequired({ override: { secondFactorToken: values.restrictedToken } }));
    route("post", VERIFICATIONS, authenticatedSession({ override: { accessToken: values.accessToken } }));
    route("get", CURRENT, currentSession());
    const { queryClient } = renderApp({ path: "/iniciar-sesion" });
    const consoleText = listenToConsole();
    const user = userEvent.setup();

    await screen.findByRole("heading", { level: 1, name: "Iniciar sesión" }, WAIT);
    await signInWith(user, values.password);
    await screen.findByRole("heading", { level: 1, name: "Verificación en dos pasos" }, WAIT);
    expect(leaks(held(queryClient), values)).toEqual([]);

    await user.type(screen.getByRole("textbox", { name: /Código de verificación/ }), code);
    await user.click(screen.getByRole("button", { name: "Verificar" }));
    await screen.findByRole("heading", { level: 1, name: "Sesión iniciada." }, WAIT);

    expect(queryClient.getQueryCache().getAll().length, "the signed in page has its query, so the search is not over an empty cache").toBeGreaterThan(0);
    // The code is searched quoted: a bare six digit number could match inside a timestamp of the cache.
    expect(leaks(held(queryClient), { ...values, code: `"${code}"` })).toEqual([]);
    expect(leaks(consoleText(), { ...values, code })).toEqual([]);
    expect(requests().find((sent) => sent.path === CURRENT)?.headers.get("Authorization")).toBe(`Bearer ${values.accessToken}`);
  });

  it("the enrollment screens: the secret, the URI, the ten codes, the tokens and the password are held by neither cache", LIMIT, async () => {
    const reply = enrollment();
    const { secret, recoveryCodes } = reply.body as { secret: string; recoveryCodes: string[] };
    const tokens = { password: `password-${crypto.randomUUID()}`, restrictedToken: `restricted-${crypto.randomUUID()}`, accessToken: `access-${crypto.randomUUID()}` };
    const values: Record<string, string> = {
      ...tokens,
      secret,
      "secret in groups": secret.match(/.{1,4}/g)?.join(" ") ?? "",
      "otpauth scheme": "otpauth://",
      ...Object.fromEntries(recoveryCodes.map((code, index) => [`recovery code ${String(index)}`, code])),
    };
    const code = typedCode();
    route("post", SESSIONS, secondFactorEnrollmentRequired({ override: { secondFactorToken: tokens.restrictedToken } }));
    route("post", ENROLLMENTS, reply);
    route("post", VERIFICATIONS, authenticatedSession({ override: { accessToken: tokens.accessToken } }));
    route("get", CURRENT, currentSession());
    const { queryClient } = renderApp({ path: "/iniciar-sesion" });
    const consoleText = listenToConsole();
    const user = userEvent.setup();
    const clean = (screenName: string) => {
      expect(leaks(held(queryClient), values), `the caches on ${screenName}`).toEqual([]);
      expect(leaks(consoleText(), values), `the console on ${screenName}`).toEqual([]);
    };

    await screen.findByRole("heading", { level: 1, name: "Iniciar sesión" }, WAIT);
    await signInWith(user, tokens.password);
    await screen.findByRole("heading", { level: 2, name: "Escanee el código QR" }, WAIT);
    clean("the secret");
    await user.click(screen.getByRole("button", { name: "Continuar" }));
    await screen.findByRole("heading", { level: 2, name: "Guarde sus códigos de recuperación" }, WAIT);
    clean("the codes");
    await user.click(screen.getByRole("checkbox", { name: /He guardado mis códigos de recuperación/ }));
    await user.click(screen.getByRole("button", { name: "Continuar" }));
    await screen.findByRole("heading", { level: 2, name: "Escriba el primer código" }, WAIT);
    clean("the first code");
    await user.type(screen.getByRole("textbox", { name: /Código de verificación/ }), code);
    await user.click(screen.getByRole("button", { name: "Activar" }));
    await screen.findByRole("heading", { level: 1, name: "Sesión iniciada." }, WAIT);
    clean("the signed in page");

    expect(leaks(held(queryClient), { code: `"${code}"` })).toEqual([]);
    expect(queryClient.getQueryCache().getAll().length).toBeGreaterThan(0);
  });
});
