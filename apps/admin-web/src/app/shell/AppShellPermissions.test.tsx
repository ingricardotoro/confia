import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it } from "vitest";
import { authenticatedSession, currentSession, organizationPage } from "../../test/contract-responses.ts";
import { installMswServer, route } from "../../test/msw-server.ts";
import { renderApp } from "../../test/render-app.tsx";

// The permissions of the session over the shell: the menu lists only what the session can use, a group with nothing left
// disappears, and an address the session cannot use shows "Sin acceso" in place of the page. The panel only hides: the
// server decides each request again, so a refusal (403) keeps its own handling.
installMswServer();

const SESSIONS = "/api/v1/auth/sessions";
const TIMEOUT = { timeout: 10_000 };

function openAs(permissions: readonly string[], path: string) {
  route("post", `${SESSIONS}/refresh`, authenticatedSession());
  route("get", `${SESSIONS}/current`, currentSession({ override: { permissions: [...permissions] } }));
  route("get", "/api/v1/organization/academic-years", organizationPage("academic-years", []));
  return renderApp({ path, readCsrf: () => "csrf-value-A" });
}

async function navigation() {
  return screen.findByRole("navigation", { name: "Navegación principal" }, TIMEOUT);
}

describe("the shell with a restricted role", () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it("lists only the entries the session can use and drops the groups left empty", { timeout: 20_000 }, async () => {
    const user = userEvent.setup();
    // Reads only, and no guardians, no ledger: the group of settings keeps two entries and the others lose some.
    openAs(["catalog:read", "charges:read", "students:read", "organization:read"], "/");

    const nav = await navigation();
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
      "Catálogo",
      "Cobros",
      "Estado de cuenta",
      "Cargos del estudiante",
      "Integridad de cargos",
      "Estructura académica",
      "Importaciones",
    ]);
  });

  it("drops a whole group when none of its entries can be used", { timeout: 20_000 }, async () => {
    openAs(["catalog:read", "charges:read"], "/");

    const nav = await navigation();
    expect(within(nav).getByRole("button", { name: "Finanzas" })).toBeInTheDocument();
    expect(within(nav).queryByRole("button", { name: "Comunidad educativa" })).not.toBeInTheDocument();
    expect(within(nav).queryByRole("button", { name: "Configuración" })).not.toBeInTheDocument();
  });

  it("shows only Home, and no group, to a session with no permission", { timeout: 20_000 }, async () => {
    openAs([], "/");

    const nav = await navigation();
    expect(
      within(nav)
        .getAllByRole("link")
        .map((link) => link.textContent),
    ).toEqual(["Inicio"]);
    expect(within(nav).queryByRole("button", { name: /Comunidad|Finanzas|Configuración/ })).not.toBeInTheDocument();
  });

  it("shows 'Sin acceso' instead of the page when the address needs a permission the session lacks", { timeout: 20_000 }, async () => {
    const { router } = openAs(["students:read"], "/ledger/accounts");

    expect(await screen.findByRole("heading", { level: 1, name: "Sin acceso" }, TIMEOUT)).toBeVisible();
    const main = screen.getByRole("main");
    expect(within(main).getByText(/Su rol no tiene permiso para ver esta página/)).toBeVisible();
    expect(within(main).queryByRole("heading", { name: "Plan de cuentas" })).not.toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/ledger/accounts");
  });

  it("leads back to Home from 'Sin acceso'", { timeout: 20_000 }, async () => {
    const user = userEvent.setup();
    const { router } = openAs([], "/charges/plans");

    await user.click(await screen.findByRole("link", { name: "Volver al inicio" }, TIMEOUT));

    await waitFor(() => {
      expect(router.state.location.pathname).toBe("/");
    });
    expect(await screen.findByRole("heading", { level: 1, name: "Sesión iniciada." }, TIMEOUT)).toBeVisible();
  });

  it("guards the page that generates charges with charges:create, though the charges can be read", { timeout: 20_000 }, async () => {
    openAs(["charges:read"], "/charges/accrual");

    expect(await screen.findByRole("heading", { level: 1, name: "Sin acceso" }, TIMEOUT)).toBeVisible();
  });
});
