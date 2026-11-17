import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ledgerAccount } from "../../../test/contract-responses.ts";
import { loadedTable, onePage } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { LedgerAccountsView, type LedgerAccountsViewProps } from "./LedgerAccountsView.tsx";

// States. AW57, AW58: the table of accounts rendered with props alone. Loading: `table.loading`. Error and empty:
// `table.error` and `table.empty`, drawn by the table in place of its body. Success: one active account that carries
// student balances and one inactive income account.
function props(override: Partial<LedgerAccountsViewProps> = {}): LedgerAccountsViewProps {
  return {
    rows: [
      ledgerAccount({ id: "00000000-0000-4000-8000-0000000000a1", code: "ACT-01", name: "Cuentas por cobrar de prueba" }),
      ledgerAccount({ id: "00000000-0000-4000-8000-0000000000a2", code: "ING-01", name: "Ingresos de prueba", type: "INCOME", holdsStudentBalances: false, active: false }),
    ],
    table: loadedTable,
    pagination: onePage,
    filters: { type: { value: "", onChange: vi.fn() }, active: { value: "", onChange: vi.fn() } },
    onCreate: vi.fn(),
    onEdit: vi.fn(),
    onToggle: vi.fn(),
    ...override,
  };
}

describe("LedgerAccountsView", () => {
  it("shows the code, name, type, student balances and state of each account, in text", () => {
    renderWithAdminI18n(<LedgerAccountsView {...props()} />);

    expect(screen.getAllByRole("columnheader").map((header) => header.textContent)).toEqual(["Código", "Nombre", "Tipo", "Saldos de estudiantes", "Estado", "Acciones"]);
    const first = within(screen.getByRole("row", { name: /ACT-01/ }));
    // The type Activo and the state Activo of the same row are two different cells.
    expect(first.getAllByText("Activo")).toHaveLength(2);
    expect(first.getByText("Sí")).toBeInTheDocument();
    const second = within(screen.getByRole("row", { name: /ING-01/ }));
    expect(second.getByText("Ingreso")).toBeInTheDocument();
    expect(second.getByText("No")).toBeInTheDocument();
    expect(second.getByText("Inactivo")).toBeInTheDocument();
  });

  it("offers each action with the account in its name, colored by what it does, and reports the row", async () => {
    const user = userEvent.setup();
    const view = props();
    renderWithAdminI18n(<LedgerAccountsView {...view} />);

    await user.click(screen.getByRole("button", { name: "Editar Ingresos de prueba" }));
    await user.click(screen.getByRole("button", { name: "Desactivar Cuentas por cobrar de prueba" }));
    await user.click(screen.getByRole("button", { name: "Activar Ingresos de prueba" }));

    expect(view.onEdit).toHaveBeenCalledWith(view.rows[1]);
    expect(view.onToggle).toHaveBeenNthCalledWith(1, view.rows[0]);
    expect(view.onToggle).toHaveBeenNthCalledWith(2, view.rows[1]);
  });

  it("offers the five types and the two states as filters and reports the choice", async () => {
    const user = userEvent.setup();
    const filters = { type: { value: "", onChange: vi.fn() }, active: { value: "", onChange: vi.fn() } };
    renderWithAdminI18n(<LedgerAccountsView {...props({ filters })} />);
    const group = within(screen.getByRole("group", { name: "Filtros" }));

    expect(within(group.getByLabelText("Tipo")).getAllByRole("option").map((option) => option.textContent)).toEqual(["Todos", "Activo", "Pasivo", "Patrimonio", "Ingreso", "Gasto"]);
    await user.selectOptions(group.getByLabelText("Tipo"), "LIABILITY");
    await user.selectOptions(group.getByLabelText("Estado"), "false");

    expect(filters.type.onChange).toHaveBeenCalledWith("LIABILITY");
    expect(filters.active.onChange).toHaveBeenCalledWith("false");
  });

  it("opens the create dialog from the one primary action", async () => {
    const user = userEvent.setup();
    const onCreate = vi.fn();
    renderWithAdminI18n(<LedgerAccountsView {...props({ onCreate })} />);

    await user.click(screen.getByRole("button", { name: "Nueva cuenta" }));

    expect(onCreate).toHaveBeenCalledOnce();
  });

  it("shows the empty state with its way forward, and the error state with the retry", async () => {
    const user = userEvent.setup();
    const onAction = vi.fn();
    const onRetry = vi.fn();
    const { rerender } = renderWithAdminI18n(
      <LedgerAccountsView {...props({ rows: [], table: { empty: { title: "Todavía no hay cuentas contables", action: { label: "Crear la primera cuenta", onAction } }, onSortChange: vi.fn() } })} />,
    );

    await user.click(screen.getByRole("button", { name: "Crear la primera cuenta" }));
    expect(onAction).toHaveBeenCalledOnce();

    rerender(<LedgerAccountsView {...props({ rows: [], table: { ...loadedTable, error: { title: "No se pudo cargar el plan de cuentas", onRetry } } })} />);
    await user.click(screen.getByRole("button", { name: "Reintentar" }));
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it("shows the result of the last action in the live region", () => {
    renderWithAdminI18n(<LedgerAccountsView {...props({ notice: { tone: "error", message: "No se pudo.", traceId: "01J8Z4K2QF9P0X" } })} />);

    expect(screen.getByText("No se pudo.")).toBeInTheDocument();
    expect(screen.getByText(/01J8Z4K2QF9P0X/)).toBeInTheDocument();
  });
});
