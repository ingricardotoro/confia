import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiFailure } from "../shared/api/api-failure.ts";
import * as api from "../shared/api/auth-operations.ts";
import { readCsrfToken } from "../shared/api/csrf-cookie.ts";
import { authenticatedSession, problem } from "../test/contract-responses.ts";
import { installMswServer, requests, route } from "../test/msw-server.ts";
import { createTestSession, renderApp } from "../test/render-app.tsx";

// admin-web-foundations-and-session, task 9.1 (part 9a; SS07 to SS13, SS34 to SS36). The whole app over the
// real transport and the test server. The CSRF cookie is injected, because jsdom does not keep a `__Host-`
// cookie set over http; the real reader is used for the empty cookie, which is what SS08 is about.
installMswServer();

const REFRESH = "/api/v1/auth/sessions/refresh";
const SIGNED_IN = "Sesión iniciada.";
const refreshes = () => requests().filter((sent) => sent.path === REFRESH);

afterEach(() => {
  vi.useRealTimers();
});

describe("restoring the session on boot", () => {
  it.each(["/", "/iniciar-sesion", "/iniciar-sesion/verificacion", "/iniciar-sesion/inscripcion", "/no-existe"])(
    "SS07: with no cookie, %s asks for no refresh and never shows the restoring screen",
    async (path) => {
      renderApp({ path, readCsrf: () => undefined });

      // The first render already is the router: there is no restoring screen to wait out.
      expect(screen.queryByText("Restaurando su sesión.")).not.toBeInTheDocument();
      await screen.findByRole("heading", { level: 1 });
      expect(refreshes()).toHaveLength(0);
    },
  );

  it.each(["/", "/iniciar-sesion"])(
    "SS07, SS08, SS10: with no cookie %s never starts the boot, the view stays restoring and the person is never held on the boot screen",
    async (path) => {
      const session = createTestSession({ readCsrf: () => undefined });
      const boot = vi.spyOn(session, "boot");

      renderApp({ path, session, readCsrf: () => undefined });

      expect(screen.queryByText("Restaurando su sesión.")).not.toBeInTheDocument();
      expect(await screen.findByRole("heading", { level: 1, name: "Iniciar sesión" })).toBeInTheDocument();
      expect(screen.queryByRole("status")).not.toBeInTheDocument();
      expect(screen.queryByText("Restauración de la sesión")).not.toBeInTheDocument();
      expect(boot).not.toHaveBeenCalled();
      expect(session.getView()).toEqual({ status: "restoring" });
      expect(refreshes()).toHaveLength(0);
    },
  );

  it("SS08: an empty cookie is an absent cookie, and no refresh goes out", async () => {
    renderApp({ path: "/", readCsrf: () => readCsrfToken("__Host-confia_csrf=") });

    expect(await screen.findByRole("heading", { level: 1, name: "Iniciar sesión" })).toBeInTheDocument();
    expect(refreshes()).toHaveLength(0);
  });

  it("SS09: a cookie restores the session with one refresh, carrying the cookie value and nothing else", async () => {
    route("post", REFRESH, authenticatedSession());

    renderApp({ path: "/", readCsrf: () => "csrf-value-A" });

    expect(await screen.findByRole("heading", { level: 1, name: SIGNED_IN })).toBeInTheDocument();
    expect(refreshes()).toHaveLength(1);
    expect(refreshes()[0]?.headers.get("X-CSRF-Token")).toBe("csrf-value-A");
    expect(refreshes()[0]?.headers.has("Authorization")).toBe(false);
    expect(refreshes()[0]?.body).toBe("");
  });

  it("SS10: the sign in route restores too, and lands on the signed in page and not on the form", async () => {
    route("post", REFRESH, authenticatedSession());

    const { router } = renderApp({ path: "/iniciar-sesion", readCsrf: () => "csrf-value-A" });

    expect(await screen.findByRole("heading", { level: 1, name: SIGNED_IN })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/");
  });

  it("SS11: a rejected refresh shows the sign in page with the expired notice and leaves nothing in memory", async () => {
    route("post", REFRESH, problem("session-expired", 401));

    const { session, queryClient } = renderApp({ path: "/", readCsrf: () => "csrf-value-A" });

    expect(await screen.findByText("Su sesión se cerró por seguridad. Vuelva a iniciar sesión.")).toBeInTheDocument();
    expect(queryClient.getQueryCache().getAll()).toEqual([]);
    // No token is held: a read fails without asking the server.
    const before = requests().length;
    await expect(session.readCurrentSession()).rejects.toBeInstanceOf(ApiFailure);
    expect(requests()).toHaveLength(before);
  });

  it("SS36: the restoring screen goes away when the refresh answers 200", async () => {
    route("post", REFRESH, authenticatedSession());

    renderApp({ path: "/", readCsrf: () => "csrf-value-A" });

    expect(await screen.findByRole("heading", { level: 1, name: SIGNED_IN })).toBeInTheDocument();
    expect(screen.queryByText("Restaurando su sesión.")).not.toBeInTheDocument();
  });
});

describe("the boot screen", () => {
  it("SS34: while the refresh is held it shows the loading indicator and the message in a polite region, and no field", async () => {
    let release: () => void = () => undefined;
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    route("post", REFRESH, authenticatedSession());
    const session = createTestSession({
      readCsrf: () => "csrf-value-A",
      api: {
        ...api,
        refreshSession: async (csrfToken) => {
          await held;
          return api.refreshSession(csrfToken);
        },
      },
    });

    renderApp({ path: "/", session, readCsrf: () => "csrf-value-A" });

    const message = await screen.findByText("Restaurando su sesión.");
    expect(message).toHaveAttribute("role", "status");
    expect(message).toHaveAttribute("aria-live", "polite");
    expect(screen.getByRole("status", { name: "Cargando" })).toBeInTheDocument();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    expect(document.forms).toHaveLength(0);

    release();
    expect(await screen.findByRole("heading", { level: 1, name: SIGNED_IN })).toBeInTheDocument();
  });

  it("SS35: a refresh that fails by network shows the error state with a named retry control", async () => {
    route("post", REFRESH, "network-error");

    renderApp({ path: "/", readCsrf: () => "csrf-value-A" });

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("No se pudo restaurar la sesión. Revise su conexión e intente de nuevo.");
    const retry = screen.getByRole("button");
    expect(retry.textContent.trim()).not.toBe("");
    expect(retry).toHaveAccessibleName();
  });

  it("SS12, SS13: a network failure is not retried alone in 60 seconds, and one activation makes one more request", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    route("post", REFRESH, "network-error", authenticatedSession());

    renderApp({ path: "/", readCsrf: () => "csrf-value-A" });
    await screen.findByRole("alert");
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000);
    });

    expect(refreshes()).toHaveLength(1);
    expect(screen.getByRole("alert")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button"));

    expect(await screen.findByRole("heading", { level: 1, name: SIGNED_IN })).toBeInTheDocument();
    await waitFor(() => {
      expect(refreshes()).toHaveLength(2);
    });
  });
});
