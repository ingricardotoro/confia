import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { cashMovement } from "../../../test/contract-responses.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { MovementsTable } from "./MovementsTable.tsx";

// States. AW57, AW58: the movements of a session rendered with props alone. Loading and error belong to the screen that holds the session. Empty: the table says
// there are no movements and offers its way forward. Success: each movement with its type, its amount as the server sent it and its notes.
describe("MovementsTable", () => {
  it("AW57: shows each movement with its type, its amount and its notes, and a text when it has none", () => {
    renderWithAdminI18n(
      <MovementsTable
        emptyAction={{ label: "Registrar depósito", onAction: vi.fn() }}
        movements={[
          cashMovement({ type: "withdrawal", amount: { amount: "50.00", currency: "HNL" }, notes: "Entrega a tesorería" }),
          cashMovement({ type: "adjustment", amount: { amount: "5.00", currency: "HNL" } }),
        ]}
      />,
    );

    const rows = screen.getAllByRole("row");
    expect(within(rows[1] as HTMLElement).getByText("Retiro")).toBeInTheDocument();
    expect(within(rows[1] as HTMLElement).getAllByText(/50\.00/).length).toBeGreaterThan(0);
    expect(within(rows[1] as HTMLElement).getByText("Entrega a tesorería")).toBeInTheDocument();
    expect(within(rows[2] as HTMLElement).getByText("Ajuste")).toBeInTheDocument();
    expect(within(rows[2] as HTMLElement).getByText("Sin notas")).toBeInTheDocument();
  });

  it("says there are no movements and gives the way forward", async () => {
    const user = userEvent.setup();
    const onAction = vi.fn();
    renderWithAdminI18n(<MovementsTable movements={[]} emptyAction={{ label: "Registrar depósito", onAction }} />);

    expect(screen.getByText("Todavía no hay movimientos")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Registrar depósito" }));

    expect(onAction).toHaveBeenCalledOnce();
  });
});
