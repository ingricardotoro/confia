import { cleanup, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  authenticatedSession,
  currentSession,
  organizationPage,
} from "../../test/contract-responses.ts";
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
  route(
    "get",
    "/api/v1/organization/academic-years",
    organizationPage("academic-years", []),
  );
  return renderApp({ path, readCsrf: () => "csrf-value-A" });
}

describe("the signed in shell", () => {
  // The open sections are remembered in the browser: every test starts from an empty storage.
  beforeEach(() => {
    window.localStorage.clear();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it(
    "draws the banner, the navigation with its registered entries and the page inside main",
    { timeout: 20_000 },
    async () => {
      const user = userEvent.setup();
      openAt("/");

      const nav = await screen.findByRole(
        "navigation",
        { name: "Navegación principal" },
        TIMEOUT,
      );
      // Every section starts closed on Home, so only the plain entry is reachable until a header is opened.
      expect(
        within(nav)
          .getAllByRole("link")
          .map((link) => link.textContent),
      ).toEqual(["Inicio"]);
      for (const name of ["Comunidad educativa", "Finanzas", "Configuración"]) {
        await user.click(within(nav).getByRole("button", { name }));
      }
      expect(
        within(nav)
          .getAllByRole("link")
          .map((link) => link.textContent),
      ).toEqual([
        "Inicio",
        "Estudiantes",
        "Matrículas",
        "Encargados",
        "Catálogo",
        "Cobros",
        "Generación de cargos",
        "Estado de cuenta",
        "Cargos del estudiante",
        "Integridad de cargos",
        "Estructura académica",
        "Importaciones",
        "Plan de cuentas",
      ]);
      expect(
        within(screen.getByRole("banner")).getByRole("button", {
          name: "Cerrar sesión",
        }),
      ).toBeVisible();
      expect(
        within(screen.getByRole("main")).getByRole("heading", {
          level: 1,
          name: "Sesión iniciada.",
        }),
      ).toBeVisible();
      expect(screen.getAllByRole("main")).toHaveLength(1);
    },
  );

  it(
    "marks the current entry with aria-current, and only that one",
    { timeout: 20_000 },
    async () => {
      openAt("/");

      expect(
        await screen.findByRole("link", { name: "Inicio" }, TIMEOUT),
      ).toHaveAttribute("aria-current", "page");
      // The entry sits in a closed section, so it is out of the accessibility tree: look it up as hidden.
      expect(
        screen.getByRole("link", { name: "Estructura académica", hidden: true }),
      ).not.toHaveAttribute("aria-current");
    },
  );

  it(
    "opens only the section of the current page, and opens another one when the navigation reaches it",
    { timeout: 20_000 },
    async () => {
      const user = userEvent.setup();
      openAt("/organization");

      await screen.findByRole("heading", { level: 1, name: "Estructura académica" }, TIMEOUT);
      const expanded = (name: string) =>
        screen.getByRole("button", { name }).getAttribute("aria-expanded");
      expect(expanded("Configuración")).toBe("true");
      expect(expanded("Finanzas")).toBe("false");
      expect(expanded("Comunidad educativa")).toBe("false");

      // Closing the open section by hand is allowed; the current page does not force it open again.
      await user.click(screen.getByRole("button", { name: "Configuración" }));
      expect(expanded("Configuración")).toBe("false");
    },
  );

  it(
    "remembers the sections the person opened, and still works when the storage fails",
    { timeout: 20_000 },
    async () => {
      const user = userEvent.setup();
      const { unmount } = openAt("/");
      await user.click(
        await screen.findByRole("button", { name: "Finanzas" }, TIMEOUT),
      );
      expect(screen.getByRole("link", { name: "Cobros" })).toBeVisible();
      unmount();

      openAt("/");
      expect(
        (await screen.findByRole("button", { name: "Finanzas" }, TIMEOUT)).getAttribute("aria-expanded"),
      ).toBe("true");
      expect(screen.getByRole("link", { name: "Cobros" })).toBeVisible();
      cleanup();

      // Reading and writing both throw: the shell still opens, collapsed, and a header still toggles.
      vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
        throw new Error("blocked");
      });
      vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
        throw new Error("blocked");
      });
      openAt("/");
      const finance = await screen.findByRole("button", { name: "Finanzas" }, TIMEOUT);
      expect(finance).toHaveAttribute("aria-expanded", "false");
      await user.click(finance);
      expect(finance).toHaveAttribute("aria-expanded", "true");
    },
  );

  it(
    "goes to the academic structure from the keyboard, moving aria-current and the heading focus",
    { timeout: 20_000 },
    async () => {
      const user = userEvent.setup();
      const { router } = openAt("/");
      await screen.findByRole(
        "navigation",
        { name: "Navegación principal" },
        TIMEOUT,
      );

      // The page heading holds the focus on arrival. A person who starts from the top of the page meets the
      // skip link first, then the entries of the sidebar in group order.
      (document.activeElement as HTMLElement).blur();
      await user.tab();
      expect(
        screen.getByRole("link", { name: "Saltar al contenido" }),
      ).toHaveFocus();
      await user.tab();
      expect(screen.getByRole("link", { name: "Inicio" })).toHaveFocus();
      // Closed sections add only their header to the tab order, in group order. Space opens the last one,
      // and then its first entry is the next stop.
      for (const name of ["Comunidad educativa", "Finanzas", "Configuración"]) {
        await user.tab();
        expect(screen.getByRole("button", { name })).toHaveFocus();
      }
      await user.keyboard(" ");
      expect(screen.getByRole("button", { name: "Configuración" })).toHaveAttribute(
        "aria-expanded",
        "true",
      );
      await user.tab();
      expect(
        screen.getByRole("link", { name: "Estructura académica" }),
      ).toHaveFocus();
      await user.keyboard("{Enter}");

      const heading = await screen.findByRole("heading", {
        level: 1,
        name: "Estructura académica",
      });
      // `/organization` alone leads to its first view.
      await waitFor(() => {
        expect(router.state.location.pathname).toBe(
          "/organization/academic-years",
        );
      });
      expect(
        screen.getByRole("link", { name: "Estructura académica" }),
      ).toHaveAttribute("aria-current", "page");
      expect(screen.getByRole("link", { name: "Inicio" })).not.toHaveAttribute(
        "aria-current",
      );
      await waitFor(() => {
        expect(heading).toHaveFocus();
      });
      expect(document.title).toBe("Estructura académica");
      expect(screen.getAllByRole("heading", { level: 1 })).toHaveLength(1);
    },
  );

  it(
    "opens the academic structure directly, with its entry current",
    { timeout: 20_000 },
    async () => {
      openAt("/organization");

      expect(
        await screen.findByRole(
          "heading",
          { level: 1, name: "Estructura académica" },
          TIMEOUT,
        ),
      ).toBeVisible();
      expect(
        screen.getByRole("link", { name: "Estructura académica" }),
      ).toHaveAttribute("aria-current", "page");
    },
  );

  it("leads to sign in, with no shell, when there is no session", async () => {
    const { router } = renderApp({ path: "/organization" });

    expect(
      await screen.findByRole("heading", { level: 1, name: "Iniciar sesión" }),
    ).toBeVisible();
    expect(router.state.location.pathname).toBe("/iniciar-sesion");
    expect(
      screen.queryByRole("navigation", { name: "Navegación principal" }),
    ).not.toBeInTheDocument();
  });
});
