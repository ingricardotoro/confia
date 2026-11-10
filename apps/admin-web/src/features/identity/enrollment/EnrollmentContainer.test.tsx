import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import * as api from "../../../shared/api/auth-operations.ts";
import { enrollment, problem, secondFactorEnrollmentRequired } from "../../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../../test/msw-server.ts";
import { createTestSession, renderApp, type RenderAppOptions } from "../../../test/render-app.tsx";

// admin-web-staff-sign-in, task 7.4 (E5, E10; SS29, SS75, SS76, SS86, SS87, SS88). The container of the enrollment up to its
// secret step, over the real app, the real transport and the test server. The secret is made at run time by the helper of
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
