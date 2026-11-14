import { fireEvent, screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { authenticatedSession, currentSession, enrollment, secondFactorEnrollmentRequired, secondFactorRequired } from "../../test/contract-responses.ts";
import { installMswServer, route } from "../../test/msw-server.ts";
import { createTestSession, renderApp } from "../../test/render-app.tsx";

// admin-web-foundations-and-session, task 9.1 (part 9a; SS27, SS30, SS32, AW18, AW19, AW59 and design.md D5).
// The whole app over the test server. What is asserted here is the guard, the heading and the focus of each
// screen; the screens themselves have their own tests.
installMswServer();

const SESSIONS = "/api/v1/auth/sessions";
const REFRESH = `${SESSIONS}/refresh`;
const CURRENT = `${SESSIONS}/current`;
const ENROLLMENTS = "/api/v1/auth/mfa/enrollments";
const CREDENTIALS = { identifier: "ana@colegio.edu.hn", password: "not-a-real-password" };

const SIGN_IN = "Iniciar sesión";
const SECOND_FACTOR = "Verificación en dos pasos";
const ENROLLMENT = "Configure su verificación en dos pasos";
const SIGNED_IN = "Sesión iniciada.";
const NOT_FOUND = "Esta página no existe.";

async function withToken(grant: "verify" | "enroll") {
  route("post", SESSIONS, grant === "verify" ? secondFactorRequired() : secondFactorEnrollmentRequired());
  const session = createTestSession();
  await session.signIn(CREDENTIALS);
  return session;
}

describe("the guards", () => {
  it("SS27, AW19: without a session the signed in page leads to sign in, and the URL keeps no destination", async () => {
    const { router } = renderApp({ path: "/" });

    expect(await screen.findByRole("heading", { level: 1, name: SIGN_IN })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/iniciar-sesion");
    expect(router.state.location.href).toBe("/iniciar-sesion");
    expect(router.state.location.search).toEqual({});
  });

  it.each(["/iniciar-sesion", "/iniciar-sesion/verificacion", "/iniciar-sesion/inscripcion"])(
    "SS30: with a session, %s leads to the signed in page",
    async (path) => {
      route("post", REFRESH, authenticatedSession());
      route("get", CURRENT, currentSession());

      const { router } = renderApp({ path, readCsrf: () => "csrf-value-A" });

      expect(await screen.findByRole("heading", { level: 1, name: SIGNED_IN })).toBeInTheDocument();
      expect(router.state.location.pathname).toBe("/");
    },
  );

  it.each([
    ["/iniciar-sesion/verificacion", "La verificación se interrumpió. Inicie sesión de nuevo para continuar."],
    [
      "/iniciar-sesion/inscripcion",
      "La inscripción se interrumpió. El código QR y los códigos de recuperación anteriores ya no sirven; al iniciar sesión se generarán nuevos.",
    ],
  ])("%s without its token leads to sign in with the interruption notice", async (path, notice) => {
    const { router } = renderApp({ path });

    expect(await screen.findByText(notice)).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/iniciar-sesion");
  });

  it("leaving the second factor screens discards its token, so it cannot be used again", async () => {
    const session = await withToken("verify");
    const { router } = renderApp({ path: "/iniciar-sesion/verificacion", session });
    await screen.findByRole("heading", { level: 1, name: SECOND_FACTOR });
    expect(session.hasSecondFactorToken("mfa-verify")).toBe(true);

    await router.navigate({ to: "/iniciar-sesion" });

    await screen.findByRole("heading", { level: 1, name: SIGN_IN });
    expect(session.hasSecondFactorToken("mfa-verify")).toBe(false);
  });
});

describe("the routes and the screens", () => {
  it("AW59: the routes are sign in, verification, enrollment, the signed in home and the registered features, and nothing else", () => {
    const { router } = renderApp({ path: "/" });

    expect(Object.keys(router.routesByPath).sort()).toEqual([
      "/",
      "/enrollments",
      "/iniciar-sesion",
      "/iniciar-sesion/inscripcion",
      "/iniciar-sesion/verificacion",
      "/organization",
      "/organization/academic-years",
      "/organization/grades",
      "/organization/modalities",
      "/organization/sections",
      "/students",
    ]);
  });

  it("SS32: every screen has its own title, one level 1 heading inside main, and the focus on it", async () => {
    const titles: string[] = [];
    const cases: { name: string; open: () => Promise<ReturnType<typeof renderApp>> }[] = [
      { name: SIGN_IN, open: () => Promise.resolve(renderApp({ path: "/iniciar-sesion" })) },
      {
        name: SECOND_FACTOR,
        open: async () => renderApp({ path: "/iniciar-sesion/verificacion", session: await withToken("verify") }),
      },
      {
        name: ENROLLMENT,
        open: async () => {
          route("post", ENROLLMENTS, enrollment());
          return renderApp({ path: "/iniciar-sesion/inscripcion", session: await withToken("enroll") });
        },
      },
      {
        name: SIGNED_IN,
        open: () => {
          route("post", REFRESH, authenticatedSession());
          route("get", CURRENT, currentSession());
          return Promise.resolve(renderApp({ path: "/", readCsrf: () => "csrf-value-A" }));
        },
      },
      { name: NOT_FOUND, open: () => Promise.resolve(renderApp({ path: "/no-existe" })) },
    ];

    for (const { name, open } of cases) {
      const { unmount } = await open();
      const heading = await screen.findByRole("heading", { level: 1, name });

      expect(screen.getAllByRole("heading", { level: 1 })).toHaveLength(1);
      expect(screen.getByRole("main")).toContainElement(heading);
      // The enrollment has sections, and each one takes the focus on its own level 2 heading when it appears (E10).
      const focused = name === ENROLLMENT ? await screen.findByRole("heading", { level: 2, name: "Escanee el código QR" }) : heading;
      await waitFor(() => {
        expect(focused).toHaveFocus();
      });
      titles.push(document.title);
      unmount();
    }

    expect(titles).toEqual([SIGN_IN, SECOND_FACTOR, ENROLLMENT, SIGNED_IN, NOT_FOUND]);
  });

  it("admin-web-staff-sign-in task 7: the enrollment route renders its secret step with the QR code and Continue", async () => {
    route("post", ENROLLMENTS, enrollment());
    renderApp({ path: "/iniciar-sesion/inscripcion", session: await withToken("enroll") });

    expect(await screen.findByRole("heading", { level: 2, name: "Escanee el código QR" })).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "Código QR para su aplicación de autenticación" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Continuar" })).toBeInTheDocument();
  });

  it("admin-web-staff-sign-in task 5: the verification route renders its form with the code field and the verify button", async () => {
    renderApp({ path: "/iniciar-sesion/verificacion", session: await withToken("verify") });
    await screen.findByRole("heading", { level: 1, name: SECOND_FACTOR });

    expect(document.forms).toHaveLength(1);
    expect(screen.getByRole("textbox", { name: /Código de verificación/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Verificar" })).toBeInTheDocument();
  });

  it("admin-web-staff-sign-in task 3: the sign in route renders its form with both fields and the sign in button", async () => {
    renderApp({ path: "/iniciar-sesion" });
    await screen.findByRole("heading", { level: 1, name: SIGN_IN });

    expect(document.forms).toHaveLength(1);
    expect(screen.getByRole("textbox", { name: /Correo electrónico/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Iniciar sesión" })).toBeInTheDocument();
  });

  it("AW18: an unknown route shows the not found screen with a link to sign in, in the text of the catalog", async () => {
    const { router } = renderApp({ path: "/no-existe" });

    expect(await screen.findByRole("heading", { level: 1, name: NOT_FOUND })).toBeInTheDocument();
    expect(screen.queryByText(/not found/i)).not.toBeInTheDocument();
    const link = screen.getByRole("link", { name: "Ir a inicio de sesión" });
    expect(link).toHaveAttribute("href", "/iniciar-sesion");

    fireEvent.click(link);

    expect(await screen.findByRole("heading", { level: 1, name: SIGN_IN })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/iniciar-sesion");
  });
});
