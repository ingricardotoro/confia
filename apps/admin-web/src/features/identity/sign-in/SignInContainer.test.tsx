import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import * as api from "../../../shared/api/auth-operations.ts";
import {
  authenticatedSession,
  currentSession,
  enrollment,
  problem,
  secondFactorEnrollmentRequired,
  secondFactorRequired,
} from "../../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../../test/msw-server.ts";
import { createTestSession, renderApp } from "../../../test/render-app.tsx";

// admin-web-staff-sign-in, task 3.1 (E3, E5, E7, E8; SS03, SS37, SS38, SS39, SS42, SS56, SS57, SS58, SS59, SS101). The
// container of the sign in screen over the real app, the real transport and the test server. Passwords are made at
// run time; none is written in this file.
installMswServer();

const SESSIONS = "/api/v1/auth/sessions";
const ENROLLMENTS = "/api/v1/auth/mfa/enrollments";
const CURRENT = `${SESSIONS}/current`;
const WRONG = "El correo o la contraseña no son correctos.";
const TIMEOUT = { timeout: 20_000 };

const logins = () => requests().filter((sent) => sent.method === "POST" && sent.path === SESSIONS);
const identifierField = () => screen.getByRole("textbox", { name: /Correo electrónico/ });
const passwordField = () => screen.getByLabelText(/Contraseña/, { selector: "input" });
const submitButton = () => screen.getByRole("button", { name: /Iniciar sesión/ });

async function open(options: Parameters<typeof renderApp>[0] = {}) {
  const user = userEvent.setup();
  const app = renderApp({ path: "/iniciar-sesion", ...options });
  await screen.findByRole("heading", { level: 1, name: "Iniciar sesión" }, TIMEOUT);
  return { user, ...app };
}

async function fillAndSubmit(user: ReturnType<typeof userEvent.setup>, identifier: string, password: string) {
  await user.type(identifierField(), identifier);
  await user.type(passwordField(), password);
  await user.click(submitButton());
}

/** A promise a test settles by hand, so the server can "retain" an answer. */
function gate() {
  let open: () => void = () => undefined;
  const closed = new Promise<void>((resolve) => {
    open = resolve;
  });
  return { closed, open };
}

describe("SignInContainer: the form before anything is sent", () => {
  it("SS37, SS38: an empty submit sends nothing, focuses the summary, and each link takes the focus to its field without touching the URL or the history", { timeout: 30_000 }, async () => {
    const { user, router } = await open();
    const before = { href: router.state.location.href, length: router.history.length };

    await user.click(submitButton());

    expect(logins()).toHaveLength(0);
    const summary = screen.getByRole("group", { name: "Hay 2 campos con errores." });
    expect(summary).toHaveFocus();
    expect(identifierField()).toHaveAttribute("aria-invalid", "true");
    expect(passwordField()).toHaveAttribute("aria-invalid", "true");

    const links = screen.getAllByRole("link", { name: "Este dato es obligatorio." });
    expect(links).toHaveLength(2);
    await user.click(links[1] as HTMLElement);

    expect(passwordField()).toHaveFocus();
    expect(router.state.location.href).toBe(before.href);
    expect(router.history.length).toBe(before.length);
  });

  it("SS39: while the answer is held the button is busy and still enabled", { timeout: 30_000 }, async () => {
    const held = gate();
    route("post", SESSIONS, secondFactorRequired());
    const session = createTestSession({
      api: {
        ...api,
        logIn: async (credentials) => {
          await held.closed;
          return api.logIn(credentials);
        },
      },
    });
    const { user } = await open({ session });

    await fillAndSubmit(user, "ana@colegio.edu.hn", crypto.randomUUID());

    await waitFor(() => {
      expect(submitButton()).toHaveAttribute("aria-busy", "true");
    });
    expect(submitButton()).toBeEnabled();
    held.open();
    await screen.findByRole("heading", { level: 1, name: "Verificación en dos pasos" }, TIMEOUT);
  });
});

describe("SignInContainer: credentials that are not accepted", () => {
  it("SS42, SS56: shows the one fixed text in an alert with the focus and keeps the identifier, whichever account was typed", { timeout: 30_000 }, async () => {
    route("post", SESSIONS, problem("authentication-failed", 401));
    const { user } = await open();

    await fillAndSubmit(user, "existe@colegio.edu.hn", crypto.randomUUID());

    const alert = await screen.findByRole("alert", {}, TIMEOUT);
    expect(alert).toHaveTextContent(WRONG);
    expect(alert).toHaveFocus();
    expect(identifierField()).toHaveValue("existe@colegio.edu.hn");
    const first = alert.textContent;

    await user.clear(identifierField());
    await user.type(identifierField(), "no-existe@colegio.edu.hn");
    await user.click(submitButton());

    await waitFor(() => {
      expect(logins()).toHaveLength(2);
    });
    expect((await screen.findByRole("alert")).textContent).toBe(first);
  });

  it("SS37: the password is not kept in the document after a failure, only what the person typed is kept in the field", { timeout: 30_000 }, async () => {
    route("post", SESSIONS, problem("authentication-failed", 401));
    const secret = `S-${crypto.randomUUID()}`;
    const { user } = await open();

    await fillAndSubmit(user, "ana@colegio.edu.hn", secret);
    await screen.findByRole("alert", {}, TIMEOUT);

    expect(passwordField()).toHaveValue(secret);
    expect(passwordField()).toHaveAttribute("type", "password");
    expect(document.body.textContent).not.toContain(secret);
  });
});

describe("SignInContainer: each answer leads to its screen", () => {
  it("SS57, SS101: authenticated reaches the signed in page and replaces the entry of sign in", { timeout: 30_000 }, async () => {
    route("post", SESSIONS, authenticatedSession());
    route("get", CURRENT, currentSession());
    const { user, router } = await open();
    const length = router.history.length;

    await fillAndSubmit(user, "ana@colegio.edu.hn", crypto.randomUUID());

    expect(await screen.findByRole("heading", { level: 1, name: "Sesión iniciada." }, TIMEOUT)).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/");
    expect(router.history.length).toBe(length);
  });

  it("SS57: second-factor-required reaches the verification and adds an entry, so going back is possible", { timeout: 30_000 }, async () => {
    route("post", SESSIONS, secondFactorRequired());
    const { user, router, session } = await open();
    const length = router.history.length;

    await fillAndSubmit(user, "ana@colegio.edu.hn", crypto.randomUUID());

    expect(await screen.findByRole("heading", { level: 1, name: "Verificación en dos pasos" }, TIMEOUT)).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/iniciar-sesion/verificacion");
    expect(router.history.length).toBe(length + 1);
    expect(session.hasSecondFactorToken("mfa-verify")).toBe(true);
  });

  it("SS57: second-factor-enrollment-required reaches the enrollment and adds an entry", { timeout: 30_000 }, async () => {
    route("post", SESSIONS, secondFactorEnrollmentRequired());
    const { user, router, session } = await open();
    const length = router.history.length;

    await fillAndSubmit(user, "ana@colegio.edu.hn", crypto.randomUUID());

    expect(await screen.findByRole("heading", { level: 1, name: "Configure su verificación en dos pasos" }, TIMEOUT)).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/iniciar-sesion/inscripcion");
    expect(router.history.length).toBe(length + 1);
    expect(session.hasSecondFactorToken("mfa-enroll")).toBe(true);
  });
});

describe("SignInContainer: the identifier that is sent and kept", () => {
  it("SS58: sends the trimmed identifier as typed, in its case, and the enrollment keeps that same value", { timeout: 30_000 }, async () => {
    route("post", SESSIONS, secondFactorEnrollmentRequired());
    route("post", ENROLLMENTS, enrollment());
    const typed = "Ana.Pérez+Admin@Colegio.edu.hn";
    const { user, session } = await open();
    // Plain text: a browser sanitizes the value of an email control, and the server must get it as typed.
    expect(identifierField()).toHaveAttribute("type", "text");

    await fillAndSubmit(user, `  ${typed}  `, crypto.randomUUID());
    await screen.findByRole("heading", { level: 1, name: "Configure su verificación en dos pasos" }, TIMEOUT);
    await session.startEnrollment();

    const sent = JSON.parse(logins()[0]?.body ?? "{}") as { identifier?: string };
    expect(sent.identifier).toBe(typed);
    expect(session.getEnrollment()?.identifier).toBe(typed);
  });

  it("SS59: with a second factor to verify the identifier is not kept for an enrollment", { timeout: 30_000 }, async () => {
    route("post", SESSIONS, secondFactorRequired());
    const { user, session } = await open();

    await fillAndSubmit(user, "ana@colegio.edu.hn", crypto.randomUUID());
    await screen.findByRole("heading", { level: 1, name: "Verificación en dos pasos" }, TIMEOUT);

    expect(await session.startEnrollment()).toEqual({ kind: "no-token" });
    expect(session.getEnrollment()).toBeUndefined();
  });
});

describe("SignInContainer: what stays out of the caches", () => {
  it("SS03: no password reaches the query cache or the mutation cache, on a failure or on a success", { timeout: 30_000 }, async () => {
    const wrong = `P-${crypto.randomUUID()}`;
    const right = `P-${crypto.randomUUID()}`;
    route("post", SESSIONS, problem("authentication-failed", 401), secondFactorRequired());
    const { user, queryClient } = await open();

    await fillAndSubmit(user, "ana@colegio.edu.hn", wrong);
    await screen.findByRole("alert", {}, TIMEOUT);
    await user.clear(passwordField());
    await user.type(passwordField(), right);
    await user.click(submitButton());
    await screen.findByRole("heading", { level: 1, name: "Verificación en dos pasos" }, TIMEOUT);

    const caches = JSON.stringify({
      queries: queryClient.getQueryCache().getAll().map((query) => ({ key: query.queryKey, data: query.state.data, error: query.state.error })),
      mutations: queryClient.getMutationCache().getAll().map((mutation) => ({ variables: mutation.state.variables, data: mutation.state.data, error: mutation.state.error })),
    });
    expect(caches).not.toContain(wrong);
    expect(caches).not.toContain(right);
    expect(logins()).toHaveLength(2);
  });
});
