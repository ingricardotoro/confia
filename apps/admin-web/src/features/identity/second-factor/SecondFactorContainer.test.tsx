import { act, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { authenticatedSession, currentSession, problem, secondFactorRequired } from "../../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../../test/msw-server.ts";
import { createTestSession, renderApp } from "../../../test/render-app.tsx";

// admin-web-staff-sign-in, task 5.1 (E4, E5, E9; SS03, SS28, SS31, SS62, SS63, SS64, SS65, SS66, SS67, SS68, SS69, SS70, SS71,
// SS73). The container of the verification screen over the real app, the real transport and the test server. Codes are made at
// run time; none is written in this file.
installMswServer();

const SESSIONS = "/api/v1/auth/sessions";
const VERIFICATIONS = "/api/v1/auth/mfa/verifications";
const CURRENT = `${SESSIONS}/current`;
const TIMEOUT = { timeout: 20_000 };
const LIMIT = { timeout: 30_000 };
const HEADING = "Verificación en dos pasos";
const INVALID_CODE = "El código de verificación no es correcto o ya expiró. Genere uno nuevo en su aplicación.";
const INVALID_RECOVERY = "El código de recuperación no es correcto o ya se usó. Revíselo e intente de nuevo.";
const INTERRUPTED = "La verificación se interrumpió. Inicie sesión de nuevo para continuar.";

const verifications = () => requests().filter((sent) => sent.method === "POST" && sent.path === VERIFICATIONS);
const sentBodies = () => verifications().map((sent) => JSON.parse(sent.body) as Record<string, unknown>);
const codeField = () => screen.getByRole("textbox", { name: /Código de verificación/ });
const recoveryField = () => screen.getByRole("textbox", { name: /Código de recuperación/ });
const submitButton = () => screen.getByRole("button", { name: /Verificar/ });
const toRecovery = () => screen.getByRole("button", { name: "Usar un código de recuperación" });
const toCode = () => screen.getByRole("button", { name: "Usar el código de la aplicación" });

/** Signs in with the simulated answer that asks for a second factor, then opens the verification with its token. */
async function open() {
  route("post", SESSIONS, secondFactorRequired());
  const session = createTestSession();
  await session.signIn({ identifier: "ana@colegio.edu.hn", password: crypto.randomUUID() });
  const user = userEvent.setup();
  const app = renderApp({ path: "/iniciar-sesion/verificacion", session });
  await screen.findByRole("heading", { level: 1, name: HEADING }, TIMEOUT);
  return { user, ...app };
}

function acceptCodes() {
  route("post", VERIFICATIONS, authenticatedSession());
  route("get", CURRENT, currentSession());
}

describe("SecondFactorContainer: what is sent", () => {
  it("SS62, SS71: a code with spaces and one with zero width characters are sent as six digits, with no recovery code", LIMIT, async () => {
    acceptCodes();
    const first = await open();
    await first.user.type(codeField(), "123 456");
    await first.user.click(submitButton());
    expect(await screen.findByRole("heading", { level: 1, name: "Sesión iniciada." }, TIMEOUT)).toBeInTheDocument();
    first.unmount();

    const second = await open();
    await second.user.click(codeField());
    await second.user.paste("1\u200B2\u200C3\u200D456");
    await second.user.click(submitButton());
    await screen.findByRole("heading", { level: 1, name: "Sesión iniciada." }, TIMEOUT);

    expect(sentBodies()).toEqual([{ code: "123456" }, { code: "123456" }]);
  });

  it("SS63: codes that are not six digits send nothing and show their error in the summary", LIMIT, async () => {
    const { user } = await open();

    for (const wrong of ["12345", "1234567", "12a456"]) {
      await user.clear(codeField());
      await user.type(codeField(), wrong);
      await user.click(submitButton());
      expect(await screen.findByRole("group", { name: "Hay 1 campo con error." })).toHaveFocus();
      expect(screen.getByRole("link", { name: "Este dato no es válido." })).toBeInTheDocument();
      expect(codeField()).toHaveAttribute("aria-invalid", "true");
    }

    expect(verifications()).toHaveLength(0);
  });

  it("SS65: in the recovery mode the request carries the recovery code in capitals, and no code", LIMIT, async () => {
    acceptCodes();
    const { user } = await open();
    await user.click(toRecovery());

    await user.type(recoveryField(), "ab-cd ef12");
    await user.click(submitButton());
    await screen.findByRole("heading", { level: 1, name: "Sesión iniciada." }, TIMEOUT);

    expect(sentBodies()).toEqual([{ recoveryCode: "AB-CDEF12" }]);
  });

  it("SS66: an empty recovery code, or one of spaces only, sends nothing and shows the field error", LIMIT, async () => {
    const { user } = await open();
    await user.click(toRecovery());

    await user.click(submitButton());
    expect(await screen.findByRole("group", { name: "Hay 1 campo con error." })).toHaveFocus();
    await user.type(recoveryField(), "   ");
    await user.click(submitButton());

    expect(await screen.findByRole("link", { name: "Este dato es obligatorio." })).toBeInTheDocument();
    expect(recoveryField()).toHaveAttribute("aria-invalid", "true");
    expect(verifications()).toHaveLength(0);
  });
});

describe("SecondFactorContainer: one mode at a time", () => {
  it("SS64: changing to the recovery code replaces the field, moves the focus to the new one, and the way back works", LIMIT, async () => {
    const { user } = await open();
    expect(screen.getByRole("heading", { level: 1 })).toHaveFocus();

    await user.click(toRecovery());

    expect(recoveryField()).toHaveFocus();
    expect(screen.queryByRole("textbox", { name: /Código de verificación/ })).not.toBeInTheDocument();

    await user.click(toCode());

    expect(codeField()).toHaveFocus();
    expect(screen.queryByRole("textbox", { name: /Código de recuperación/ })).not.toBeInTheDocument();
  });

  it("SS65: the value and the error of a mode do not survive a change of mode, so only the field in view is ever sent", LIMIT, async () => {
    const { user } = await open();
    await user.type(codeField(), "123456");
    await user.click(toRecovery());
    await user.type(recoveryField(), "ab");
    await user.click(toCode());
    expect(codeField()).toHaveValue("");

    await user.click(submitButton());
    expect(await screen.findByRole("group", { name: "Hay 1 campo con error." })).toBeInTheDocument();
    await user.click(toRecovery());

    expect(recoveryField()).toHaveValue("");
    expect(screen.queryByRole("group")).not.toBeInTheDocument();
    expect(verifications()).toHaveLength(0);
  });
});

describe("SecondFactorContainer: a code that is not accepted", () => {
  it("SS67: shows the fixed text of the code in an alert with the focus, and stays in the mode with what was typed", LIMIT, async () => {
    route("post", VERIFICATIONS, problem("second-factor-invalid", 401));
    const { user } = await open();

    await user.type(codeField(), "123456");
    await user.click(submitButton());

    const alert = await screen.findByRole("alert", {}, TIMEOUT);
    expect(alert).toHaveTextContent(INVALID_CODE);
    expect(alert).toHaveFocus();
    expect(codeField()).toHaveValue("123456");
    expect(screen.queryByRole("textbox", { name: /Código de recuperación/ })).not.toBeInTheDocument();
  });

  it("SS68: in the recovery mode shows the text of the recovery code, which does not ask for a code of the application", LIMIT, async () => {
    route("post", VERIFICATIONS, problem("second-factor-invalid", 401));
    const { user } = await open();
    await user.click(toRecovery());

    await user.type(recoveryField(), "abcd");
    await user.click(submitButton());

    const alert = await screen.findByRole("alert", {}, TIMEOUT);
    expect(alert).toHaveTextContent(INVALID_RECOVERY);
    expect(alert).not.toHaveTextContent("aplicación");
    expect(recoveryField()).toHaveValue("abcd");
  });

  it("a 500 shows the unexpected text with its trace id, and the person can send again", LIMIT, async () => {
    route("post", VERIFICATIONS, problem("internal-error", 500, { traceId: "01J8Z4K2QF9P0X" }));
    const { user } = await open();

    await user.type(codeField(), "123456");
    await user.click(submitButton());

    const alert = await screen.findByRole("alert", {}, TIMEOUT);
    expect(alert).toHaveTextContent("01J8Z4K2QF9P0X");
    await user.click(submitButton());
    await waitFor(() => {
      expect(verifications()).toHaveLength(2);
    });
  });
});

describe("SecondFactorContainer: a restricted token that ended", () => {
  it.each([
    ["SS69", "token-expired"],
    ["SS70", "token-invalid"],
  ] as const)("%s: %s discards the token and leads to sign in with the notice of the interrupted verification", LIMIT, async (_id, code) => {
    route("post", VERIFICATIONS, problem(code, 401));
    const { user, router, session } = await open();

    await user.type(codeField(), "123456");
    await user.click(submitButton());

    expect(await screen.findByText(INTERRUPTED, {}, TIMEOUT)).toBeInTheDocument();
    expect(await screen.findByRole("heading", { level: 1, name: "Iniciar sesión" })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/iniciar-sesion");
    expect(session.hasSecondFactorToken("mfa-verify")).toBe(false);
    expect(document.forms).toHaveLength(1);
  });

  it("SS28: without a restricted token the verification leads to sign in with its notice, and sends nothing", LIMIT, async () => {
    const { router } = renderApp({ path: "/iniciar-sesion/verificacion" });

    expect(await screen.findByText(INTERRUPTED, {}, TIMEOUT)).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/iniciar-sesion");
    expect(requests()).toHaveLength(0);
  });
});

describe("SecondFactorContainer: leaving the screen", () => {
  it("SS71: a 200 reaches the signed in page and replaces the entry of the verification, and the restricted token is gone", LIMIT, async () => {
    acceptCodes();
    const { user, router, session } = await open();
    const length = router.history.length;

    await user.type(codeField(), "123456");
    await user.click(submitButton());

    expect(await screen.findByRole("heading", { level: 1, name: "Sesión iniciada." }, TIMEOUT)).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/");
    expect(router.history.length).toBe(length);
    expect(session.hasSecondFactorToken("mfa-verify")).toBe(false);
  });

  it("SS73: the way back leads to sign in with no notice, and the token is discarded", LIMIT, async () => {
    const { user, router, session } = await open();

    await user.click(screen.getByRole("button", { name: "Volver al inicio de sesión" }));

    expect(await screen.findByRole("heading", { level: 1, name: "Iniciar sesión" }, TIMEOUT)).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/iniciar-sesion");
    expect(screen.queryByText(INTERRUPTED)).not.toBeInTheDocument();
    expect(screen.queryByText(/Su sesión se cerró/)).not.toBeInTheDocument();
    expect(session.hasSecondFactorToken("mfa-verify")).toBe(false);
  });

  it("SS31: going back with the browser and forward again leads to sign in with the notice, and the old token sends nothing", LIMIT, async () => {
    route("post", SESSIONS, secondFactorRequired());
    const user = userEvent.setup();
    const { router, session } = renderApp({ path: "/iniciar-sesion" });
    await screen.findByRole("heading", { level: 1, name: "Iniciar sesión" }, TIMEOUT);
    await user.type(screen.getByRole("textbox", { name: /Correo electrónico/ }), "ana@colegio.edu.hn");
    await user.type(screen.getByLabelText(/Contraseña/, { selector: "input" }), crypto.randomUUID());
    await user.click(screen.getByRole("button", { name: /Iniciar sesión/ }));
    await screen.findByRole("heading", { level: 1, name: HEADING }, TIMEOUT);

    act(() => {
      router.history.back();
    });
    await screen.findByRole("heading", { level: 1, name: "Iniciar sesión" }, TIMEOUT);
    expect(session.hasSecondFactorToken("mfa-verify")).toBe(false);
    act(() => {
      router.history.forward();
    });

    expect(await screen.findByText(INTERRUPTED, {}, TIMEOUT)).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/iniciar-sesion");
    expect(verifications()).toHaveLength(0);
  });
});

describe("SecondFactorContainer: what stays out of the caches", () => {
  it("SS03: no code reaches the query cache or the mutation cache, on a failure or on a success", LIMIT, async () => {
    const baseline = JSON.stringify([authenticatedSession().body, currentSession().body]);
    let wrong = String(Math.floor(100_000 + Math.random() * 900_000));
    while (baseline.includes(wrong)) wrong = String(Math.floor(100_000 + Math.random() * 900_000));
    const right = `RC${crypto.randomUUID().replaceAll("-", "").toUpperCase()}`;
    route("post", VERIFICATIONS, problem("second-factor-invalid", 401), authenticatedSession());
    route("get", CURRENT, currentSession());
    const { user, queryClient } = await open();

    await user.type(codeField(), wrong);
    await user.click(submitButton());
    await screen.findByRole("alert", {}, TIMEOUT);
    await user.click(toRecovery());
    await user.type(recoveryField(), right);
    await user.click(submitButton());
    await screen.findByRole("heading", { level: 1, name: "Sesión iniciada." }, TIMEOUT);

    const caches = JSON.stringify({
      queries: queryClient.getQueryCache().getAll().map((query) => ({ key: query.queryKey, data: query.state.data, error: query.state.error })),
      mutations: queryClient.getMutationCache().getAll().map((mutation) => ({ variables: mutation.state.variables, data: mutation.state.data, error: mutation.state.error })),
    });
    expect(caches).not.toContain(wrong);
    expect(caches).not.toContain(right);
    expect(sentBodies()).toEqual([{ code: wrong }, { recoveryCode: right }]);
  });
});
