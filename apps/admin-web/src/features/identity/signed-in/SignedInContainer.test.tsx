import { fireEvent, screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ApiFailure } from "../../../shared/api/api-failure.ts";
import * as api from "../../../shared/api/auth-operations.ts";
import { authenticatedSession, currentSession, noContent, problem } from "../../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../../test/msw-server.ts";
import { createTestSession, renderApp } from "../../../test/render-app.tsx";

// admin-web-foundations-and-session, task 9.1 (part 9b; SS103 to SS112). The container of the minimal signed
// in page over the real transport and the test server. The page is reached the way a person reaches it:
// the cookie is there, the boot restores the session, and the page reads the current session.
installMswServer();

const SESSIONS = "/api/v1/auth/sessions";
const REFRESH = `${SESSIONS}/refresh`;
const CURRENT = `${SESSIONS}/current`;
const CONFIRMED = "Su sesión está activa.";
const EXPIRED = "Su sesión se cerró por seguridad. Vuelva a iniciar sesión.";

const only = (method: string, path: string) => requests().filter((sent) => sent.method === method && sent.path === path);

function open(csrf: { value: string | undefined } = { value: "csrf-value-A" }, held?: ReturnType<typeof createTestSession>) {
  route("post", REFRESH, authenticatedSession());
  return renderApp({ path: "/", readCsrf: () => csrf.value, ...(held ? { session: held } : {}) });
}

/** A promise a test settles by hand, so the server can "retain" an answer. */
function gate() {
  let open: () => void = () => undefined;
  const closed = new Promise<void>((resolve) => {
    open = resolve;
  });
  return { closed, open };
}

// The first render of the whole app in a file builds the module graph cold, which can outlast the 1 s wait
// of findBy and the 5 s of a test on a loaded machine; the waits are explicit and no assertion depends on them.
describe("reading the session", () => {
  it("SS103: shows no data of the person, only the heading, the confirmation and the sign out button", { timeout: 20_000 }, async () => {
    const sentinels = { accountId: crypto.randomUUID(), institutionId: crypto.randomUUID(), sessionId: crypto.randomUUID() };
    route("get", CURRENT, currentSession({ override: sentinels }));

    open();

    expect(await screen.findByText(CONFIRMED, {}, { timeout: 10_000 })).toBeInTheDocument();
    for (const sentinel of Object.values(sentinels)) expect(document.body.textContent).not.toContain(sentinel);
    expect(screen.getByRole("heading", { level: 1 })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Cerrar sesión" })).toBeInTheDocument();
  });

  it("SS104: while the read is held it shows the loading state and not yet the confirmation", async () => {
    const held = gate();
    route("get", CURRENT, currentSession());
    const session = createTestSession({
      readCsrf: () => "csrf-value-A",
      api: {
        ...api,
        getCurrentSession: async (token) => {
          await held.closed;
          return api.getCurrentSession(token);
        },
      },
    });

    open(undefined, session);

    expect(await screen.findByText("Cargando su sesión.")).toHaveAttribute("aria-live", "polite");
    expect(screen.queryByText(CONFIRMED)).not.toBeInTheDocument();

    held.open();
    expect(await screen.findByText(CONFIRMED)).toBeInTheDocument();
  });

  it("SS105: a read that fails by network shows the error state, and its retry reads again", async () => {
    route("get", CURRENT, "network-error", currentSession());

    open();

    expect(await screen.findByRole("alert")).toHaveTextContent("No se pudo conectar con el servidor.");
    fireEvent.click(screen.getByRole("button"));

    expect(await screen.findByText(CONFIRMED)).toBeInTheDocument();
    expect(only("GET", CURRENT)).toHaveLength(2);
  });

  it("SS106: a 401 to the read leads to sign in with the expired notice", async () => {
    route("get", CURRENT, problem("token-expired", 401));

    const { queryClient } = open();

    expect(await screen.findByText(EXPIRED)).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1, name: "Iniciar sesión" })).toBeInTheDocument();
    expect(queryClient.getQueryCache().getAll()).toEqual([]);
  });
});

describe("signing out", () => {
  it("SS107: a 204 sends the DELETE with the CSRF token and no Authorization, and leaves memory and cache empty", async () => {
    route("get", CURRENT, currentSession());
    route("delete", CURRENT, noContent());
    const { session, queryClient } = open();
    await screen.findByText(CONFIRMED);

    fireEvent.click(screen.getByRole("button", { name: "Cerrar sesión" }));

    expect(await screen.findByRole("heading", { level: 1, name: "Iniciar sesión" })).toBeInTheDocument();
    const [sent, ...rest] = only("DELETE", CURRENT);
    expect(rest).toEqual([]);
    expect(sent?.headers.get("X-CSRF-Token")).toBe("csrf-value-A");
    expect(sent?.headers.has("Authorization")).toBe(false);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(queryClient.getQueryCache().getAll()).toEqual([]);
    const before = requests().length;
    await expect(session.readCurrentSession()).rejects.toBeInstanceOf(ApiFailure);
    expect(requests()).toHaveLength(before);
  });

  it("SS108: reloading after the sign out asks for no refresh, because the server deleted the cookies", async () => {
    route("get", CURRENT, currentSession());
    route("delete", CURRENT, noContent());
    const csrf: { value: string | undefined } = { value: "csrf-value-A" };
    open(csrf);
    await screen.findByText(CONFIRMED);
    fireEvent.click(screen.getByRole("button", { name: "Cerrar sesión" }));
    await screen.findByRole("heading", { level: 1, name: "Iniciar sesión" });
    csrf.value = undefined;
    const refreshesBefore = only("POST", REFRESH).length;

    renderApp({ path: "/", readCsrf: () => csrf.value });

    expect(await screen.findAllByRole("heading", { level: 1, name: "Iniciar sesión" })).not.toHaveLength(0);
    expect(only("POST", REFRESH)).toHaveLength(refreshesBefore);
  });

  it.each([
    ["network", "network-error" as const],
    ["403 csrf-invalid", problem("csrf-invalid", 403)],
  ])("SS109: a sign out that fails by %s stays on the page with an error alert, and a retry can finish it", async (_name, failure) => {
    route("get", CURRENT, currentSession());
    route("delete", CURRENT, failure, noContent());
    const { session } = open();
    await screen.findByText(CONFIRMED);

    fireEvent.click(screen.getByRole("button", { name: "Cerrar sesión" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("No se pudo cerrar la sesión. Su sesión sigue activa.");
    expect(session.getView()).toEqual({ status: "authenticated" });
    expect(screen.getByText(CONFIRMED)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Reintentar cierre de sesión" }));

    expect(await screen.findByRole("heading", { level: 1, name: "Iniciar sesión" })).toBeInTheDocument();
    expect(only("DELETE", CURRENT)).toHaveLength(2);
  });

  it("SS110: three clicks while the server holds the answer make one DELETE, and the button is busy and not disabled", async () => {
    const held = gate();
    route("get", CURRENT, currentSession());
    route("delete", CURRENT, noContent());
    const session = createTestSession({
      readCsrf: () => "csrf-value-A",
      api: {
        ...api,
        endCurrentSession: async (csrfToken) => {
          await held.closed;
          return api.endCurrentSession(csrfToken);
        },
      },
    });
    open(undefined, session);
    await screen.findByText(CONFIRMED);
    const button = screen.getByRole("button", { name: "Cerrar sesión" });

    fireEvent.click(button);
    fireEvent.click(button);
    fireEvent.click(button);

    await waitFor(() => {
      expect(button).toHaveAttribute("aria-busy", "true");
    });
    expect(button).toBeEnabled();
    held.open();
    await screen.findByRole("heading", { level: 1, name: "Iniciar sesión" });
    expect(only("DELETE", CURRENT)).toHaveLength(1);
  });

  it("SS111: when the server already expired the session, its 204 still leads to sign in, with no error message", async () => {
    route("get", CURRENT, currentSession());
    route("delete", CURRENT, noContent());
    open();
    await screen.findByText(CONFIRMED);

    fireEvent.click(screen.getByRole("button", { name: "Cerrar sesión" }));

    expect(await screen.findByRole("heading", { level: 1, name: "Iniciar sesión" })).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.queryByText(EXPIRED)).not.toBeInTheDocument();
  });

  it("SS112: reloading with the session expired by inactivity gets 401 session-expired and shows the expired notice", async () => {
    route("post", REFRESH, problem("session-expired", 401));

    renderApp({ path: "/", readCsrf: () => "csrf-value-A" });

    expect(await screen.findByText(EXPIRED)).toBeInTheDocument();
    expect(only("POST", REFRESH)).toHaveLength(1);
  });
});
