import { screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { paymentConcept, paymentConceptPage, taxRatePage } from "../../test/contract-responses.ts";
import { installMswServer, route } from "../../test/msw-server.ts";
import { renderCatalog } from "../../test/render-catalog.tsx";

// identity-session-permissions: the write actions of the payment concepts are drawn only with the permission the server asks
// for them (catalog:create to create, catalog:update to edit and to activate or deactivate). The panel only hides.
installMswServer();

const CONCEPTS = "/api/v1/catalog/payment-concepts";
const RATES = "/api/v1/catalog/tax-rates";
const TIMEOUT = { timeout: 10_000 };

async function openAs(permissions: readonly string[], rows: unknown[] = [paymentConcept({ code: "COL", name: "Colegiatura" })]) {
  route("get", CONCEPTS, paymentConceptPage(rows));
  route("get", RATES, taxRatePage([]));
  renderCatalog("/catalog", { permissions });
  const table = await screen.findByRole("table", { name: "Conceptos de pago de la institución" }, TIMEOUT);
  await waitFor(() => {
    expect(table).not.toHaveAttribute("aria-busy");
  }, TIMEOUT);
}

describe("the payment concepts with a restricted role", () => {
  it("draws no create, edit or activate action for a role that only reads", async () => {
    await openAs(["catalog:read"]);

    expect(screen.getByRole("row", { name: /Colegiatura/ })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Nuevo concepto" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Editar / })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Desactivar / })).not.toBeInTheDocument();
  });

  it("splits the actions by permission: create without update draws only the create button", async () => {
    await openAs(["catalog:read", "catalog:create"]);

    expect(screen.getByRole("button", { name: "Nuevo concepto" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Editar / })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Desactivar / })).not.toBeInTheDocument();
  });

  it("draws every action for a role that can create and update", async () => {
    await openAs(["catalog:read", "catalog:create", "catalog:update"]);

    expect(screen.getByRole("button", { name: "Nuevo concepto" })).toBeInTheDocument();
    const row = within(screen.getByRole("row", { name: /Colegiatura/ }));
    expect(row.getByRole("button", { name: /^Editar / })).toBeInTheDocument();
    expect(row.getByRole("button", { name: /^Desactivar / })).toBeInTheDocument();
  });

  it("does not send a role that cannot create to a form from the empty state: it leaves a way to read the list again", async () => {
    await openAs(["catalog:read"], []);

    expect(await screen.findByText("Todavía no hay registros. Su rol solo puede consultar esta información.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Crear el primer concepto" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Actualizar la lista" })).toBeInTheDocument();
  });
});
