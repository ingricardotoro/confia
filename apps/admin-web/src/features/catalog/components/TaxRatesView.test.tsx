import { fireEvent, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { taxRate } from "../../../test/contract-responses.ts";
import { loadedTable, onePage } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { TaxRatesView, type TaxRatesViewProps } from "./TaxRatesView.tsx";

// States. AW57, AW58: the table of tax rates rendered with props alone. Loading: `table.loading`. Error and empty:
// `table.error` and `table.empty`, drawn by the table in place of its body. Success: one active rate and one inactive.
function props(override: Partial<TaxRatesViewProps> = {}): TaxRatesViewProps {
  return {
    rows: [
      taxRate({ id: "00000000-0000-4000-8000-0000000000a1", code: "IMP-A", name: "Impuesto de prueba A" }),
      taxRate({ id: "00000000-0000-4000-8000-0000000000a2", code: "IMP-B", name: "Impuesto de prueba B", isActive: false }),
    ],
    table: loadedTable,
    pagination: onePage,
    filters: { active: { value: "", onChange: vi.fn() } },
    hrefOf: (row) => `/catalog/tax-rates/${row.id}`,
    onOpen: vi.fn(),
    onCreate: vi.fn(),
    onEdit: vi.fn(),
    onToggle: vi.fn(),
    ...override,
  };
}

describe("TaxRatesView", () => {
  it("shows the code, name and state of each rate, in text, and no percentage", () => {
    renderWithAdminI18n(<TaxRatesView {...props()} />);

    expect(screen.getAllByRole("columnheader").map((header) => header.textContent)).toEqual(["Código", "Nombre", "Estado", "Acciones"]);
    const first = within(screen.getByRole("row", { name: /IMP-A/ }));
    expect(first.getByText("Impuesto de prueba A")).toBeInTheDocument();
    expect(first.getByText("Activo")).toBeInTheDocument();
    expect(within(screen.getByRole("row", { name: /IMP-B/ })).getByText("Inactivo")).toBeInTheDocument();
    expect(screen.queryByText(/%/)).not.toBeInTheDocument();
  });

  it("makes the code a link to the detail that the router follows on a plain click only", () => {
    const onOpen = vi.fn();
    const view = props({ onOpen });
    renderWithAdminI18n(<TaxRatesView {...view} />);
    const link = screen.getByRole("link", { name: "IMP-B" });

    expect(link).toHaveAttribute("href", "/catalog/tax-rates/00000000-0000-4000-8000-0000000000a2");
    fireEvent.click(link, { ctrlKey: true });
    expect(onOpen).not.toHaveBeenCalled();
    fireEvent.click(link);
    expect(onOpen).toHaveBeenCalledWith(view.rows[1]);
  });

  it("offers the two states as a filter and reports the choice", async () => {
    const user = userEvent.setup();
    const filters = { active: { value: "", onChange: vi.fn() } };
    renderWithAdminI18n(<TaxRatesView {...props({ filters })} />);
    const select = within(screen.getByRole("group", { name: "Filtros" })).getByLabelText("Estado");

    expect(within(select).getAllByRole("option").map((option) => option.textContent)).toEqual(["Todos", "Activo", "Inactivo"]);
    await user.selectOptions(select, "false");

    expect(filters.active.onChange).toHaveBeenCalledWith("false");
  });

  it("reports the row of the edit and of the toggle, each button naming the rate, and the creation", async () => {
    const user = userEvent.setup();
    const handlers = { onCreate: vi.fn(), onEdit: vi.fn(), onToggle: vi.fn() };
    const view = props(handlers);
    renderWithAdminI18n(<TaxRatesView {...view} />);

    await user.click(screen.getByRole("button", { name: "Nueva tasa" }));
    await user.click(screen.getByRole("button", { name: "Editar Impuesto de prueba A" }));
    await user.click(screen.getByRole("button", { name: "Activar Impuesto de prueba B" }));
    await user.click(screen.getByRole("button", { name: "Desactivar Impuesto de prueba A" }));

    expect(handlers.onCreate).toHaveBeenCalledTimes(1);
    expect(handlers.onEdit).toHaveBeenCalledWith(view.rows[0]);
    expect(handlers.onToggle).toHaveBeenNthCalledWith(1, view.rows[1]);
    expect(handlers.onToggle).toHaveBeenNthCalledWith(2, view.rows[0]);
  });

  it("the loading state shows a busy table, the error state a retry, and the empty state the way forward", () => {
    const { rerender } = renderWithAdminI18n(<TaxRatesView {...props({ rows: [], table: { ...loadedTable, loading: true } })} />);
    expect(screen.getByRole("table")).toHaveAttribute("aria-busy", "true");

    rerender(<TaxRatesView {...props({ rows: [], table: { ...loadedTable, error: { title: "No se pudo cargar", onRetry: vi.fn() } } })} />);
    expect(screen.getByRole("button", { name: "Reintentar" })).toBeInTheDocument();

    rerender(<TaxRatesView {...props({ rows: [] })} />);
    expect(screen.getByText("vacío")).toBeInTheDocument();
  });

  it("tells the result of the last action, and busies only the button of the row being toggled", () => {
    renderWithAdminI18n(<TaxRatesView {...props({ togglingId: "00000000-0000-4000-8000-0000000000a1", notice: { tone: "success", message: "Se desactivó «Impuesto de prueba A»." } })} />);

    expect(screen.getByText("Se desactivó «Impuesto de prueba A».")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Desactivar Impuesto de prueba A" })).toHaveAttribute("aria-busy", "true");
    expect(screen.getByRole("button", { name: "Activar Impuesto de prueba B" })).not.toHaveAttribute("aria-busy", "true");
  });
});
