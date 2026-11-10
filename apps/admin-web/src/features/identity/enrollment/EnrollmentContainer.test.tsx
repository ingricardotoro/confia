import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import * as api from "../../../shared/api/auth-operations.ts";
import { authenticatedSession, currentSession, enrollment, problem, secondFactorEnrollmentRequired } from "../../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../../test/msw-server.ts";
import { createTestSession, renderApp, type RenderAppOptions } from "../../../test/render-app.tsx";

// admin-web-staff-sign-in, tasks 7.4 and 8.1 (E5, E10; SS29, SS75, SS76, SS86 to SS88, SS89 to SS94, SS96 to SS99). The container
// of the enrollment, from its secret step to the first code, over the real app, the real transport and the test server. The secret is made at run time by the helper of
// contract responses; none is written in this file.
installMswServer();

const SESSIONS = "/api/v1/auth/sessions";
const ENROLLMENTS = "/api/v1/auth/mfa/enrollments";
const TIMEOUT = { timeout: 20_000 };
const LIMIT = { timeout: 30_000 };
const HEADING = "Configure su verificación en dos pasos";
const SECTION = "Escanee el código QR";
const FAILED = "No se pudo preparar la verificación en dos pasos.";
const IDENTIFIER = "ana.pérez@colegio.edu.hn";

const enrollments = () => requests().filter((sent) => sent.method === "POST" && sent.path === ENROLLMENTS);

/** Signs in with the answer that asks for an enrollment, then opens the screen with its restricted token. */
async function open(options: RenderAppOptions = {}) {
  route("post", SESSIONS, secondFactorEnrollmentRequired());
  const session = createTestSession(options);
  await session.signIn({ identifier: IDENTIFIER, password: crypto.randomUUID() });
  return renderApp({ path: "/iniciar-sesion/inscripcion", session, ...options });
}

const secretOf = (reply: ReturnType<typeof enrollment>) => (reply.body as { secret: string }).secret;
const groups = (secret: string) => secret.match(/.{1,4}/g)?.join(" ") ?? "";

describe("EnrollmentContainer: the secret step", () => {
  it("shows the QR code and the key in groups, and focuses its section", LIMIT, async () => {
    const reply = enrollment();
    route("post", ENROLLMENTS, reply);
    await open();

    const section = await screen.findByRole("heading", { level: 2, name: SECTION }, TIMEOUT);

    await waitFor(() => {
      expect(section).toHaveFocus();
    });
    expect(screen.getByRole("heading", { level: 1, name: HEADING })).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "Código QR para su aplicación de autenticación" })).toBeInTheDocument();
    expect(screen.getByText(groups(secretOf(reply)))).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Copiar clave" })).toBeInTheDocument();
  });

  it("Continue moves the draft to the next step", LIMIT, async () => {
    route("post", ENROLLMENTS, enrollment());
    const { session } = await open();
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Continuar" }, TIMEOUT));

    expect(session.getEnrollment()?.step).toBe("recovery-codes");
  });
});

describe("EnrollmentContainer: the request", () => {
  it("SS75: the double mount of StrictMode sends one enrollment request", LIMIT, async () => {
    route("post", ENROLLMENTS, enrollment());
    await open({ strict: true });

    await screen.findByRole("heading", { level: 2, name: SECTION }, TIMEOUT);

    expect(enrollments()).toHaveLength(1);
  });

  it("SS76: while the request is pending it shows a spinner and a polite message, and no secret", LIMIT, async () => {
    let release: () => void = () => undefined;
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    const reply = enrollment();
    route("post", ENROLLMENTS, reply);
    await open({
      api: {
        ...api,
        enrollSecondFactor: async (token) => {
          await held;
          return api.enrollSecondFactor(token);
        },
      },
    });

    const message = await screen.findByText("Preparando su verificación en dos pasos.", {}, TIMEOUT);

    expect(message).toHaveAttribute("aria-live", "polite");
    expect(screen.getAllByRole("status").length).toBeGreaterThan(0);
    expect(screen.queryByRole("img")).not.toBeInTheDocument();
    expect(screen.queryByText(groups(secretOf(reply)))).not.toBeInTheDocument();
    release();
    await screen.findByRole("heading", { level: 2, name: SECTION }, TIMEOUT);
  });
});

describe("EnrollmentContainer: when the request does not give a secret", () => {
  it("SS86: 409 mfa-already-enrolled leads to sign in with the notice of an enrolled factor", LIMIT, async () => {
    route("post", ENROLLMENTS, problem("mfa-already-enrolled", 409));
    const { router, session } = await open();

    expect(
      await screen.findByText("Su cuenta ya tiene un segundo factor inscrito. Inicie sesión y use su código de verificación.", {}, TIMEOUT),
    ).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/iniciar-sesion");
    expect(session.hasSecondFactorToken("mfa-enroll")).toBe(false);
  });

  it.each(["token-expired", "token-invalid"] as const)("SS88: 401 %s leads to sign in with the notice of an interrupted enrollment", LIMIT, async (code) => {
    route("post", ENROLLMENTS, problem(code, 401));
    const { router, session } = await open();

    expect(await screen.findByText(/La inscripción se interrumpió\./, {}, TIMEOUT)).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/iniciar-sesion");
    expect(session.hasSecondFactorToken("mfa-enroll")).toBe(false);
  });

  it("SS29: without a restricted token the enrollment leads to sign in with its notice", LIMIT, async () => {
    const { router } = renderApp({ path: "/iniciar-sesion/inscripcion" });

    expect(await screen.findByText(/La inscripción se interrumpió\./, {}, TIMEOUT)).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/iniciar-sesion");
    expect(enrollments()).toHaveLength(0);
  });

  it.each([
    ["a 503", problem("capacity-exceeded", 503), "El servicio está ocupado en este momento."],
    ["a 429", problem("too-many-requests", 429), "Hizo demasiadas solicitudes seguidas."],
    ["a network error", "network-error" as const, "No se pudo conectar con el servidor."],
  ])("SS87: %s shows the error with a retry, and only the retry sends the next request", LIMIT, async (_name, failure, detail) => {
    route("post", ENROLLMENTS, failure, enrollment());
    await open({ strict: true });
    const user = userEvent.setup();

    const alert = await screen.findByRole("alert", {}, TIMEOUT);
    expect(alert).toHaveTextContent(FAILED);
    expect(alert).toHaveTextContent(detail);
    expect(screen.queryByRole("img")).not.toBeInTheDocument();
    expect(enrollments()).toHaveLength(1);

    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    await screen.findByRole("heading", { level: 2, name: SECTION }, TIMEOUT);
    expect(enrollments()).toHaveLength(2);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("SS87, AW64: a 500 shows its trace id with a control to copy it, and neither title nor detail", LIMIT, async () => {
    route("post", ENROLLMENTS, problem("internal-error", 500, { traceId: "01J8Z4K2QF9P0X" }));
    await open();

    const alert = await screen.findByRole("alert", {}, TIMEOUT);

    expect(alert).toHaveTextContent(FAILED);
    expect(screen.getByText("01J8Z4K2QF9P0X")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Copiar identificador" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Reintentar" })).toBeInTheDocument();
  });
});

const VERIFICATIONS = "/api/v1/auth/mfa/verifications";
const CURRENT = `${SESSIONS}/current`;
const CODES_HEADING = "Guarde sus códigos de recuperación";
const FIRST_CODE_HEADING = "Escriba el primer código";
const CONFIRM = /He guardado mis códigos de recuperación/;
const INVALID_CODE = "El código de verificación no es correcto o ya expiró. Genere uno nuevo en su aplicación.";
const CONFIRM_REQUIRED = "Confirme que guardó sus códigos de recuperación para continuar.";

const codesOf = (reply: ReturnType<typeof enrollment>) => (reply.body as { recoveryCodes: string[] }).recoveryCodes;
/** What an authenticator shows: made at run time, so the file holds no code. */
const sixDigits = () => Array.from(crypto.getRandomValues(new Uint8Array(6)), (byte) => byte % 10).join("");
const verifications = () => requests().filter((sent) => sent.method === "POST" && sent.path === VERIFICATIONS);
const codeField = () => screen.getByRole("textbox", { name: /Código de verificación/ });
const shownText = () => document.body.textContent;

/** Opens the enrollment and presses Continue on the secret step. */
async function toCodes(options: RenderAppOptions = {}) {
  const reply = enrollment();
  route("post", ENROLLMENTS, reply);
  const app = await open(options);
  const user = userEvent.setup();
  await user.click(await screen.findByRole("button", { name: "Continuar" }, TIMEOUT));
  await screen.findByRole("heading", { level: 2, name: CODES_HEADING }, TIMEOUT);
  return { ...app, user, reply };
}

/** Confirms the codes and presses Continue. */
async function toFirstCode(options: RenderAppOptions = {}) {
  const reached = await toCodes(options);
  await reached.user.click(screen.getByRole("checkbox", { name: CONFIRM }));
  await reached.user.click(screen.getByRole("button", { name: "Continuar" }));
  await screen.findByRole("heading", { level: 2, name: FIRST_CODE_HEADING }, TIMEOUT);
  return reached;
}

describe("EnrollmentContainer: the recovery codes step", () => {
  it("SS89: shows the ten codes of the answer as the text of a list, and focuses its section", LIMIT, async () => {
    const { reply } = await toCodes();

    const items = screen.getAllByRole("listitem").map((item) => item.textContent);
    expect(items).toEqual(codesOf(reply));
    expect(items).toHaveLength(10);
    await waitFor(() => {
      expect(screen.getByRole("heading", { level: 2, name: CODES_HEADING })).toHaveFocus();
    });
  });

  it("SS90: Continue without the box does not advance, is not disabled, and shows the summary with the focus and a link to the box", LIMIT, async () => {
    const { user, session } = await toCodes();
    const button = screen.getByRole("button", { name: "Continuar" });
    expect(button).toBeEnabled();

    await user.click(button);

    const summary = await screen.findByRole("group", { name: "Hay 1 campo con error." });
    await waitFor(() => {
      expect(summary).toHaveFocus();
    });
    expect(session.getEnrollment()?.step).toBe("recovery-codes");
    expect(screen.getByRole("heading", { level: 2, name: CODES_HEADING })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Continuar" })).toBeEnabled();

    await user.click(screen.getByRole("link", { name: CONFIRM_REQUIRED }));
    expect(screen.getByRole("checkbox", { name: CONFIRM })).toHaveFocus();
  });

  it("SS91: with the box checked, Continue advances to the first code", LIMIT, async () => {
    const { session } = await toFirstCode();

    expect(session.getEnrollment()?.step).toBe("first-code");
    expect(screen.queryByRole("group", { name: /campo/ })).not.toBeInTheDocument();
  });

  it("SS92: no field exists before the first code step, not on the secret, the codes, nor after a Continue that failed", LIMIT, async () => {
    route("post", ENROLLMENTS, enrollment());
    await open();
    const user = userEvent.setup();
    await screen.findByRole("heading", { level: 2, name: SECTION }, TIMEOUT);
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Continuar" }));
    await screen.findByRole("heading", { level: 2, name: CODES_HEADING }, TIMEOUT);
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Continuar" }));
    await screen.findByRole("group", { name: "Hay 1 campo con error." });
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
  });

  it("SS93, SS03: after advancing no node holds a code, going back does not return them, and no cache holds them", LIMIT, async () => {
    const { reply, user, session, queryClient } = await toFirstCode();
    const codes = codesOf(reply);

    expect(session.getEnrollment()?.recoveryCodes).toBeUndefined();
    for (const code of codes) expect(shownText()).not.toContain(code);

    await user.click(screen.getByRole("button", { name: "Ver de nuevo el código QR" }));
    await screen.findByRole("heading", { level: 2, name: SECTION }, TIMEOUT);
    for (const code of codes) expect(shownText()).not.toContain(code);
    expect(screen.queryByRole("heading", { name: CODES_HEADING })).not.toBeInTheDocument();

    const caches = JSON.stringify([queryClient.getQueryCache().getAll(), queryClient.getMutationCache().getAll()]);
    for (const code of codes) expect(caches).not.toContain(code);
    expect(caches).not.toContain(secretOf(reply));
  });

  it("SS94: See the QR code again returns to the secret with no new request, and its Continue goes to the first code", LIMIT, async () => {
    const { reply, user, session } = await toFirstCode();

    await user.click(screen.getByRole("button", { name: "Ver de nuevo el código QR" }));

    await screen.findByRole("heading", { level: 2, name: SECTION }, TIMEOUT);
    expect(screen.getByText(groups(secretOf(reply)))).toBeInTheDocument();
    expect(enrollments()).toHaveLength(1);
    await user.click(screen.getByRole("button", { name: "Continuar" }));
    expect(await screen.findByRole("heading", { level: 2, name: FIRST_CODE_HEADING }, TIMEOUT)).toBeInTheDocument();
    expect(session.getEnrollment()?.step).toBe("first-code");
    expect(screen.queryByRole("heading", { name: CODES_HEADING })).not.toBeInTheDocument();
    expect(enrollments()).toHaveLength(1);
  });
});

describe("EnrollmentContainer: the first code", () => {
  it("SS96: the code is sent alone, as a TOTP, with the enrollment token, and the answer leads home with a replacement", LIMIT, async () => {
    route("post", VERIFICATIONS, authenticatedSession());
    route("get", CURRENT, currentSession());
    const { user, router, session } = await toFirstCode();
    const code = sixDigits();

    await user.type(codeField(), code);
    await user.click(screen.getByRole("button", { name: "Activar" }));

    expect(await screen.findByRole("heading", { level: 1, name: "Sesión iniciada." }, TIMEOUT)).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/");
    const [sent] = verifications();
    expect(JSON.parse(sent?.body ?? "")).toEqual({ code });
    expect(sent?.headers.get("Authorization")).toMatch(/^Bearer .+/);
    expect(sent?.headers.get("Authorization")).toBe(requests().find((request) => request.path === ENROLLMENTS)?.headers.get("Authorization"));
    expect(session.getEnrollment()).toBeUndefined();
    expect(session.hasSecondFactorToken("mfa-enroll")).toBe(false);
  });

  it("SS96: a code that is not six digits sends nothing, and one with spaces is sent as six digits", LIMIT, async () => {
    route("post", VERIFICATIONS, authenticatedSession());
    route("get", CURRENT, currentSession());
    const { user } = await toFirstCode();

    await user.type(codeField(), "12345");
    await user.click(screen.getByRole("button", { name: "Activar" }));
    expect(await screen.findByRole("group", { name: "Hay 1 campo con error." })).toBeInTheDocument();
    expect(verifications()).toHaveLength(0);

    await user.clear(codeField());
    await user.click(codeField());
    await user.paste("123 456");
    await user.click(screen.getByRole("button", { name: "Activar" }));
    await screen.findByRole("heading", { level: 1, name: "Sesión iniciada." }, TIMEOUT);
    expect(verifications().map((sent) => JSON.parse(sent.body) as unknown)).toEqual([{ code: "123456" }]);
  });

  it("SS97: a code that is not accepted shows the fixed text of the TOTP, and the QR code and the key stay in view", LIMIT, async () => {
    route("post", VERIFICATIONS, problem("second-factor-invalid", 401));
    const { user, reply, session } = await toFirstCode();

    await user.type(codeField(), sixDigits());
    await user.click(screen.getByRole("button", { name: "Activar" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(INVALID_CODE);
    expect(screen.getByRole("img", { name: "Código QR para su aplicación de autenticación" })).toBeInTheDocument();
    expect(screen.getByText(groups(secretOf(reply)))).toBeInTheDocument();
    expect(session.getEnrollment()?.step).toBe("first-code");
    expect(session.hasSecondFactorToken("mfa-enroll")).toBe(true);
  });

  it("an enrollment token that ended leads to sign in with the notice of an interrupted enrollment", LIMIT, async () => {
    route("post", VERIFICATIONS, problem("token-expired", 401));
    const { user, router, session } = await toFirstCode();

    await user.type(codeField(), sixDigits());
    await user.click(screen.getByRole("button", { name: "Activar" }));

    expect(await screen.findByText(/La inscripción se interrumpió\./, {}, TIMEOUT)).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/iniciar-sesion");
    expect(session.getEnrollment()).toBeUndefined();
  });
});

describe("EnrollmentContainer: leaving", () => {
  it("SS98: leaving for sign in forgets everything, and going back to the enrollment leads to sign in with the notice", LIMIT, async () => {
    const { reply, router, session } = await toFirstCode();

    await router.navigate({ to: "/iniciar-sesion" });

    await screen.findByRole("heading", { level: 1, name: "Iniciar sesión" }, TIMEOUT);
    expect(session.getEnrollment()).toBeUndefined();
    expect(session.hasSecondFactorToken("mfa-enroll")).toBe(false);
    expect(shownText()).not.toContain(secretOf(reply));

    router.history.back();

    expect(await screen.findByText(/La inscripción se interrumpió\./, {}, TIMEOUT)).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/iniciar-sesion");
    expect(enrollments()).toHaveLength(1);
  });

  it("SS99: after the enrollment, going to it again leads home and sends no enrollment request", LIMIT, async () => {
    route("post", VERIFICATIONS, authenticatedSession());
    route("get", CURRENT, currentSession());
    const { user, router } = await toFirstCode();
    await user.type(codeField(), sixDigits());
    await user.click(screen.getByRole("button", { name: "Activar" }));
    await screen.findByRole("heading", { level: 1, name: "Sesión iniciada." }, TIMEOUT);

    await router.navigate({ to: "/iniciar-sesion/inscripcion" });

    expect(router.state.location.pathname).toBe("/");
    expect(screen.queryByRole("heading", { level: 2 })).not.toBeInTheDocument();
    expect(enrollments()).toHaveLength(1);
  });
});

/** An activation that waits for `release()` before it reaches the server, so a test can act while it is in flight. */
function heldActivation() {
  let release: () => void = () => undefined;
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  return {
    release: () => {
      release();
    },
    options: {
      api: {
        ...api,
        completeSecondFactor: async (token: string, proof: Parameters<typeof api.completeSecondFactor>[1]) => {
          await held;
          return api.completeSecondFactor(token, proof);
        },
      },
    } satisfies RenderAppOptions,
  };
}

describe("EnrollmentContainer: an activation that is in flight", () => {
  it("an answer that arrives after the person left for sign in navigates nobody", LIMIT, async () => {
    route("post", VERIFICATIONS, authenticatedSession());
    route("get", CURRENT, currentSession());
    const held = heldActivation();
    const { user, router } = await toFirstCode(held.options);
    await user.type(codeField(), sixDigits());
    await user.click(screen.getByRole("button", { name: "Activar" }));
    await router.navigate({ to: "/iniciar-sesion" });
    await screen.findByRole("heading", { level: 1, name: "Iniciar sesión" }, TIMEOUT);

    held.release();
    await waitFor(() => {
      expect(verifications()).toHaveLength(1);
    }, TIMEOUT);
    await new Promise((resolve) => setTimeout(resolve, 300));

    expect(router.state.location.pathname).toBe("/iniciar-sesion");
    expect(screen.queryByRole("heading", { level: 1, name: "Sesión iniciada." })).not.toBeInTheDocument();
  });

  it("while it is in flight, See the QR code again does nothing: the draft stays at the first code", LIMIT, async () => {
    route("post", VERIFICATIONS, problem("second-factor-invalid", 401));
    const held = heldActivation();
    const { user, session } = await toFirstCode(held.options);
    await user.type(codeField(), sixDigits());
    await user.click(screen.getByRole("button", { name: "Activar" }));

    await user.click(screen.getByRole("button", { name: "Ver de nuevo el código QR" }));

    expect(session.getEnrollment()?.step).toBe("first-code");
    held.release();
    expect(await screen.findByRole("alert", {}, TIMEOUT)).toHaveTextContent(INVALID_CODE);
  });
});

describe("EnrollmentContainer: a recovery codes step without codes", () => {
  it.each([
    ["missing", undefined],
    ["empty", [] as string[]],
  ])("with the codes %s, is a failure with a way out, not an empty list the person could confirm", LIMIT, async (_case, codes) => {
    route("post", ENROLLMENTS, enrollment());
    route("post", SESSIONS, secondFactorEnrollmentRequired());
    const real = createTestSession();
    await real.signIn({ identifier: IDENTIFIER, password: crypto.randomUUID() });
    // A draft that reached the codes step with none: the session cannot do it, so the container is shown one by hand.
    // The same object until the draft changes, as the store requires.
    let seen: ReturnType<typeof real.getEnrollment>;
    let shown: ReturnType<typeof real.getEnrollment>;
    const session = {
      ...real,
      getEnrollment: () => {
        const held = real.getEnrollment();
        if (held !== seen) {
          seen = held;
          shown = held === undefined ? undefined : { ...held, step: "recovery-codes" as const, recoveryCodes: codes };
        }
        return shown;
      },
    };
    const { router } = renderApp({ path: "/iniciar-sesion/inscripcion", session });
    const user = userEvent.setup();

    const alert = await screen.findByRole("alert", {}, TIMEOUT);

    expect(alert).toHaveTextContent(FAILED);
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
    expect(screen.queryByRole("list")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Continuar" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));
    expect(await screen.findByText(/La inscripción se interrumpió./, {}, TIMEOUT)).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/iniciar-sesion");
  });
});
