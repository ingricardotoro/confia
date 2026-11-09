import { act, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import * as api from "../../../shared/api/auth-operations.ts";
import { authenticatedSession, problem, secondFactorEnrollmentRequired } from "../../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../../test/msw-server.ts";
import { createTestSession, renderApp } from "../../../test/render-app.tsx";

// admin-web-staff-sign-in, task 4.1 (E3, E5, E6, E7; SS41, SS43, SS44, SS45, SS46, SS47, SS48, SS49, SS50, SS60, AW49,
// AW54, AW64). Every state of the sign in screen after the form is sent, over the real app, the real transport and the
// test server. Passwords are made at run time; none is written in this file.
installMswServer();

const SESSIONS = "/api/v1/auth/sessions";
const REFRESH = `${SESSIONS}/refresh`;
const ENROLLMENTS = "/api/v1/auth/mfa/enrollments";
const TIMEOUT = { timeout: 20_000 };
const LIMIT = { timeout: 30_000 };
const RATE_LIMITED = "Hizo demasiadas solicitudes seguidas. Espere unos segundos e intente de nuevo.";
const WAITING = "Verificando sus datos. Esto puede tardar hasta 30 segundos.";
const DONE = "Ya puede intentar de nuevo.";

const logins = () => requests().filter((sent) => sent.method === "POST" && sent.path === SESSIONS);
const identifierField = () => screen.getByRole("textbox", { name: /Correo electrónico/ });
const passwordField = () => screen.getByLabelText(/Contraseña/, { selector: "input" });
const submitButton = () => screen.getByRole("button", { name: /Iniciar sesión/ });
const ACTIVE = '[role="alert"], [role="status"], [aria-live]:not([aria-live="off"])';

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

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe("SignInContainer: a 400 with the fields it refused", () => {
  it("SS41: shows the reason under the field and in the summary, with the focus, and never the rejected value", LIMIT, async () => {
    const secret = `S-${crypto.randomUUID()}`;
    route("post", SESSIONS, problem("validation-failed", 400, { errors: [{ field: "password", reason: "max" }] }));
    const { user } = await open();

    await fillAndSubmit(user, "ana@colegio.edu.hn", secret);

    const summary = await screen.findByRole("group", { name: "Hay 1 campo con error." }, TIMEOUT);
    expect(summary).toHaveFocus();
    expect(passwordField()).toHaveAttribute("aria-invalid", "true");
    expect(passwordField()).toHaveAccessibleDescription("Este dato es demasiado largo.");
    await user.click(screen.getByRole("link", { name: "Este dato es demasiado largo." }));
    expect(passwordField()).toHaveFocus();
    // Values of fields are not text of the document; every message is.
    expect(document.body.textContent).not.toContain(secret);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});

describe("SignInContainer: the limit of requests", () => {
  const limited = (seconds: string) => ({ ...problem("too-many-requests", 429), headers: { "Retry-After": seconds } });

  it("SS43: shows the fixed text with the count outside every active region, and the count goes down", LIMIT, async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval", "Date"] });
    route("post", SESSIONS, limited("15"));
    const { user } = await open();

    await fillAndSubmit(user, "ana@colegio.edu.hn", crypto.randomUUID());

    const alert = await screen.findByRole("alert", {}, TIMEOUT);
    expect(alert).toHaveTextContent(RATE_LIMITED);
    expect(alert).toHaveFocus();
    const count = screen.getByText("Puede intentar de nuevo en 15 s.");
    expect(count.closest(ACTIVE)).toBeNull();
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(screen.getByText("Puede intentar de nuevo en 14 s.")).toBeInTheDocument();
    expect(alert).toHaveTextContent(RATE_LIMITED);
  });

  it("SS44, SS45, SS46: the active regions change twice from the notice to the end, a submit in between sends nothing, and one after sends", LIMIT, async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval", "Date"] });
    route("post", SESSIONS, limited("15"));
    const { user } = await open();
    await user.type(identifierField(), "ana@colegio.edu.hn");
    await user.type(passwordField(), crypto.randomUUID());

    const snapshots: string[] = [];
    // Every element that announces: the two roles and any `aria-live` element. The function matcher sees each element, which no role query does for aria-live.
    const read = () =>
      screen
        .queryAllByText((_, node) => node?.matches(ACTIVE) ?? false)
        .map((node) => node.textContent)
        .join("|");
    const record = () => {
      const now = read();
      if (snapshots.at(-1) !== now) snapshots.push(now);
    };
    const observer = new MutationObserver(record);
    observer.observe(document.body, { subtree: true, childList: true, characterData: true, attributes: true });
    record();
    await user.click(submitButton());
    await screen.findByRole("alert", {}, TIMEOUT);

    await user.click(submitButton());
    expect(logins()).toHaveLength(1);
    expect(submitButton()).toBeEnabled();
    expect(screen.getByRole("alert")).toHaveFocus();
    for (let second = 0; second < 15; second += 1) {
      // Async, so the observer sees each second apart: a synchronous loop would coalesce them into one batch.
      await act(async () => {
        vi.advanceTimersByTime(1000);
        await Promise.resolve();
      });
    }
    record();
    observer.disconnect();

    const first = snapshots.findIndex((text) => text.includes(RATE_LIMITED));
    expect(first).toBeGreaterThan(-1);
    // R4: the wait region empties in the same commit in which the notice appears, so it is not a change of its own.
    expect(snapshots[first]).not.toContain("Verificando");
    expect(snapshots.slice(first)).toHaveLength(2);
    expect(snapshots.at(-1)).toContain(DONE);
    expect(screen.queryByText(/Puede intentar de nuevo en/)).not.toBeInTheDocument();

    await user.click(submitButton());
    await waitFor(() => {
      expect(logins()).toHaveLength(2);
    });
  });
});

describe("SignInContainer: the other failures of the request", () => {
  it("SS47: a 503 shows the busy text in an alert with the focus, sends nothing by itself and a second activation sends again", LIMIT, async () => {
    route("post", SESSIONS, problem("capacity-exceeded", 503));
    const { user } = await open();

    await fillAndSubmit(user, "ana@colegio.edu.hn", crypto.randomUUID());

    const alert = await screen.findByRole("alert", {}, TIMEOUT);
    expect(alert).toHaveTextContent("El servicio está ocupado en este momento. Espere un momento e intente de nuevo.");
    expect(alert).toHaveFocus();
    expect(logins()).toHaveLength(1);
    await user.click(submitButton());
    await waitFor(() => {
      expect(logins()).toHaveLength(2);
    });
  });

  it("SS49: a network error shows its message in an alert, keeps what was typed and makes one attempt", LIMIT, async () => {
    route("post", SESSIONS, "network-error");
    const { user } = await open();

    await fillAndSubmit(user, "ana@colegio.edu.hn", "x-clave");

    const alert = await screen.findByRole("alert", {}, TIMEOUT);
    expect(alert).toHaveTextContent("No se pudo conectar con el servidor. Revise su conexión e intente de nuevo.");
    expect(identifierField()).toHaveValue("ana@colegio.edu.hn");
    expect(passwordField()).toHaveValue("x-clave");
    expect(logins()).toHaveLength(1);
  });

  it("SS50, AW54: a 500 shows the generic message, the trace id and the control that copies it, in an alert with the focus", LIMIT, async () => {
    route("post", SESSIONS, problem("internal-error", 500, { traceId: "01J8Z4K2QF9P0X" }));
    const { user } = await open();

    await fillAndSubmit(user, "ana@colegio.edu.hn", crypto.randomUUID());

    const alert = await screen.findByRole("alert", {}, TIMEOUT);
    expect(alert).toHaveTextContent("Ocurrió un error inesperado y la operación no se completó.");
    expect(alert).toHaveTextContent("01J8Z4K2QF9P0X");
    expect(alert).toHaveFocus();
    expect(screen.getByRole("button", { name: "Copiar identificador" })).toBeInTheDocument();
  });

  it("AW49: neither the title nor the detail of the answer reaches the document, an accessible attribute or the console", LIMIT, async () => {
    const spy = vi.spyOn(console, "error");
    const reply = problem("authentication-failed", 401);
    route("post", SESSIONS, { ...reply, body: { ...(reply.body as object), title: "CENTINELA-TITULO", detail: "CENTINELA-DETALLE" } });
    const { user } = await open();

    await fillAndSubmit(user, "ana@colegio.edu.hn", crypto.randomUUID());
    await screen.findByRole("alert", {}, TIMEOUT);

    expect(document.documentElement.outerHTML).not.toMatch(/CENTINELA/);
    expect(JSON.stringify(spy.mock.calls)).not.toMatch(/CENTINELA/);
  });

  it("AW64: a 200 that breaks the contract shows the generic message and keeps no token", LIMIT, async () => {
    route("post", SESSIONS, { status: 200, body: { status: "authenticated", accessToken: 7 }, contentType: "application/json" });
    const { user, session } = await open();

    await fillAndSubmit(user, "ana@colegio.edu.hn", crypto.randomUUID());

    const alert = await screen.findByRole("alert", {}, TIMEOUT);
    expect(alert).toHaveTextContent("Ocurrió un error inesperado y la operación no se completó.");
    expect(session.getView().status).not.toBe("authenticated");
    expect(session.hasSecondFactorToken("mfa-verify")).toBe(false);
    expect(session.hasSecondFactorToken("mfa-enroll")).toBe(false);
  });
});

describe("SignInContainer: the wait of a long answer", () => {
  it("SS48: while the answer is held the polite region says that the data is being verified, and the button stays busy", LIMIT, async () => {
    let release: () => void = () => undefined;
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    route("post", SESSIONS, authenticatedSession());
    const session = createTestSession({
      api: {
        ...api,
        logIn: async (credentials) => {
          await held;
          return api.logIn(credentials);
        },
      },
    });
    const { user } = await open({ session });

    await fillAndSubmit(user, "ana@colegio.edu.hn", crypto.randomUUID());

    const region = await screen.findByText(WAITING, {}, TIMEOUT);
    expect(region.closest("[aria-live=polite]")).not.toBeNull();
    expect(submitButton()).toHaveAttribute("aria-busy", "true");
    release();
    await waitFor(() => {
      expect(screen.queryByText(WAITING)).not.toBeInTheDocument();
    });
  });
});

describe("SignInContainer: the arrival notices", () => {
  const arrivals = [
    ["session-expired", "Su sesión se cerró por seguridad. Vuelva a iniciar sesión."],
    ["second-factor-interrupted", "La verificación se interrumpió. Inicie sesión de nuevo para continuar."],
    ["enrollment-interrupted", "La inscripción se interrumpió. El código QR y los códigos de recuperación anteriores ya no sirven; al iniciar sesión se generarán nuevos."],
    ["second-factor-already-enrolled", "Su cuenta ya tiene un segundo factor inscrito. Inicie sesión y use su código de verificación."],
  ] as const;

  async function arriveWith(reason: (typeof arrivals)[number][0]) {
    if (reason === "session-expired") {
      route("post", REFRESH, problem("session-expired", 401));
      return renderApp({ path: "/", readCsrf: () => "csrf-value-A" });
    }
    if (reason === "second-factor-already-enrolled") {
      route("post", SESSIONS, secondFactorEnrollmentRequired());
      route("post", ENROLLMENTS, problem("mfa-already-enrolled", 409));
      const { user, ...app } = await open();
      await fillAndSubmit(user, "ana@colegio.edu.hn", crypto.randomUUID());
      await screen.findByRole("heading", { level: 1, name: "Configure su verificación en dos pasos" }, TIMEOUT);
      await act(async () => {
        // The enrollment screen of a later task does this when the server says the account is enrolled (SS86).
        const outcome = await app.session.startEnrollment();
        if (outcome.kind === "failed") app.session.discardSecondFactor("second-factor-already-enrolled");
      });
      await app.router.navigate({ to: "/iniciar-sesion" });
      return app;
    }
    const path = reason === "second-factor-interrupted" ? "/iniciar-sesion/verificacion" : "/iniciar-sesion/inscripcion";
    return renderApp({ path });
  }

  it.each(arrivals)("SS60: %s shows its own notice in a polite region, keeps the focus on the heading and leaves no trace in the URL or the history", LIMIT, async (reason, text) => {
    const app = await arriveWith(reason);

    const notice = await screen.findByText(text, {}, TIMEOUT);
    const region = notice.closest('[role="status"]');
    expect(region).not.toBeNull();
    expect(region).not.toHaveFocus();
    expect(screen.getByRole("heading", { level: 1, name: "Iniciar sesión" })).toHaveFocus();
    expect(app.router.state.location.pathname).toBe("/iniciar-sesion");
    expect(app.router.state.location.href).not.toMatch(/aviso|notice|expired|interrupted/i);
    expect(JSON.stringify(window.history.state ?? null)).not.toMatch(/expired|interrupted|enrolled|notice/i);
    expect(JSON.stringify(app.router.history.location.state)).not.toMatch(/expired|interrupted|enrolled|notice/i);

    app.unmount();
    renderApp({ path: "/iniciar-sesion" });
    await screen.findByRole("heading", { level: 1, name: "Iniciar sesión" }, TIMEOUT);
    expect(screen.queryByText(text)).not.toBeInTheDocument();
  });

  it("SS60: the notice of a second factor already enrolled is a warning, the others information", LIMIT, async () => {
    const enrolled = await arriveWith("second-factor-already-enrolled");
    const warning = (await screen.findByText(arrivals[3][1], {}, TIMEOUT)).closest('[role="status"]');
    expect(warning).toHaveTextContent("Advertencia");
    enrolled.unmount();

    await arriveWith("session-expired");
    const info = (await screen.findByText(arrivals[0][1], {}, TIMEOUT)).closest('[role="status"]');
    expect(info).toHaveTextContent("Información");
  });

  it("E7: the notice is hidden when the first submit starts, so it does not live next to the error of a new attempt", LIMIT, async () => {
    route("post", SESSIONS, problem("authentication-failed", 401));
    const { user } = await arriveWith("enrollment-interrupted").then((app) => ({ ...app, user: userEvent.setup() }));
    await screen.findByText(arrivals[2][1], {}, TIMEOUT);

    await user.click(submitButton());
    expect(screen.getByText(arrivals[2][1])).toBeInTheDocument();

    await fillAndSubmit(user, "ana@colegio.edu.hn", crypto.randomUUID());

    await screen.findByRole("alert", {}, TIMEOUT);
    expect(screen.queryByText(arrivals[2][1])).not.toBeInTheDocument();
  });
});
