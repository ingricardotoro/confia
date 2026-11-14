import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { authenticatedSession, currentSession, organizationPage } from "../../test/contract-responses.ts";
import { installMswServer, route } from "../../test/msw-server.ts";
import { renderApp } from "../../test/render-app.tsx";

// The signed in shell over the whole app and the test server: the navigation is driven by the entries the
// features register, the current page is announced, and the pages render inside the outlet.
installMswServer();

const SESSIONS = "/api/v1/auth/sessions";
const TIMEOUT = { timeout: 10_000 };

function openAt(path: string) {
  route("post", `${SESSIONS}/refresh`, authenticatedSession());
  route("get", `${SESSIONS}/current`, currentSession());
  // The academic structure opens on its first view, which lists the years.
  route("get", "/api/v1/organization/academic-years", organizationPage("academic-years", []));
  return renderApp({ path, readCsrf: () => "csrf-value-A" });
}

describe("the signed in shell", () => {
  it("draws the banner, the navigation with its registered entries and the page inside main", { timeout: 20_000 }, async () => {
    openAt("/");

    const nav = await screen.findByRole("navigation", { name: "Navegación principal" }, TIMEOUT);
    expect(within(nav).getAllByRole("link").map((link) => link.textContent)).toEqual(["Inicio", "Estructura académica", "Estudiantes", "Matrículas", "Encargados"]);
    expect(within(screen.getByRole("banner")).getByRole("button", { name: "Cerrar sesión" })).toBeVisible();
    expect(within(screen.getByRole("main")).getByRole("heading", { level: 1, name: "Sesión iniciada." })).toBeVisible();
    expect(screen.getAllByRole("main")).toHaveLength(1);
  });

  it("marks the current entry with aria-current, and only that one", { timeout: 20_000 }, async () => {
    openAt("/");

    expect(await screen.findByRole("link", { name: "Inicio" }, TIMEOUT)).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Estructura académica" })).not.toHaveAttribute("aria-current");
  });

  it("goes to the academic structure from the keyboard, moving aria-current and the heading focus", { timeout: 20_000 }, async () => {
    const user = userEvent.setup();
    const { router } = openAt("/");
    await screen.findByRole("navigation", { name: "Navegación principal" }, TIMEOUT);

    // The page heading holds the focus on arrival. A person who starts from the top of the page meets the
    // skip link first, then the sign out of the header, then the entries in order.
    (document.activeElement as HTMLElement).blur();
    await user.tab();
    expect(screen.getByRole("link", { name: "Saltar al contenido" })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole("button", { name: "Cerrar sesión" })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole("link", { name: "Inicio" })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole("link", { name: "Estructura académica" })).toHaveFocus();
    await user.keyboard("{Enter}");

    const heading = await screen.findByRole("heading", { level: 1, name: "Estructura académica" });
    // `/organization` alone leads to its first view.
    await waitFor(() => {
      expect(router.state.location.pathname).toBe("/organization/academic-years");
    });
    expect(screen.getByRole("link", { name: "Estructura académica" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Inicio" })).not.toHaveAttribute("aria-current");
    await waitFor(() => {
      expect(heading).toHaveFocus();
    });
    expect(document.title).toBe("Estructura académica");
    expect(screen.getAllByRole("heading", { level: 1 })).toHaveLength(1);
  });

  it("opens the academic structure directly, with its entry current", { timeout: 20_000 }, async () => {
    openAt("/organization");

    expect(await screen.findByRole("heading", { level: 1, name: "Estructura académica" }, TIMEOUT)).toBeVisible();
    expect(screen.getByRole("link", { name: "Estructura académica" })).toHaveAttribute("aria-current", "page");
  });

  it("leads to sign in, with no shell, when there is no session", async () => {
    const { router } = renderApp({ path: "/organization" });

    expect(await screen.findByRole("heading", { level: 1, name: "Iniciar sesión" })).toBeVisible();
    expect(router.state.location.pathname).toBe("/iniciar-sesion");
    expect(screen.queryByRole("navigation", { name: "Navegación principal" })).not.toBeInTheDocument();
  });
});
