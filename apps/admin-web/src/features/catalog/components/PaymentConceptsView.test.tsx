import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { paymentConcept } from "../../../test/contract-responses.ts";
import { loadedTable, onePage } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { PaymentConceptsView, type PaymentConceptsViewProps } from "./PaymentConceptsView.tsx";

// States. AW57, AW58: the table of payment concepts rendered with props alone. Loading: `table.loading`. Error and empty:
// `table.error` and `table.empty`, drawn by the table in place of its body. Success: the rows, one active and
// recurring and taxable, one inactive and neither.
function props(override: Partial<PaymentConceptsViewProps> = {}): PaymentConceptsViewProps {
  return {
    rows: [
      paymentConcept({ code: "COL", name: "Colegiatura" }),
      paymentConcept({ code: "MUL", name: "Multa por retraso", category: "FINE", isRecurring: false, isTaxable: false, isActive: false }),
    ],
    table: loadedTable,
    pagination: onePage,
    filters: { category: { value: "", onChange: vi.fn() }, active: { value: "", onChange: vi.fn() } },
    onCreate: vi.fn(),
    onEdit: vi.fn(),
    onToggle: vi.fn(),
    ...override,
  };
}

describe("PaymentConceptsView", () => {
  it("shows the code, name, category, recurrence, tax condition and state of each concept, in text", () => {
    renderWithAdminI18n(<PaymentConceptsView {...props()} />);

    expect(screen.getAllByRole("columnheader").map((header) => header.textContent)).toEqual([
      "Código",
      "Nombre",
      "Categoría",
      "Recurrente",
      "Gravado",
      "Estado",
      "Acciones",
    ]);
    const first = within(screen.getByRole("row", { name: /Colegiatura/ }));
    expect(first.getByText("COL")).toBeInTheDocument();
    expect(first.getAllByText("Colegiatura")).toHaveLength(2);
    expect(first.getAllByText("Sí")).toHaveLength(2);
    expect(first.getByText("Activo")).toBeInTheDocument();
    const second = within(screen.getByRole("row", { name: /Multa por retraso/ }));
    expect(second.getByText("Multa")).toBeInTheDocument();
    expect(second.getAllByText("No")).toHaveLength(2);
    expect(second.getByText("Inactivo")).toBeInTheDocument();
  });

  it("reports the row of the edit and of the toggle, and the creation", async () => {
    const user = userEvent.setup();
    const handlers = { onCreate: vi.fn(), onEdit: vi.fn(), onToggle: vi.fn() };
    const view = props(handlers);
    renderWithAdminI18n(<PaymentConceptsView {...view} />);

    await user.click(screen.getByRole("button", { name: "Nuevo concepto" }));
    await user.click(screen.getByRole("button", { name: "Editar Colegiatura" }));
    await user.click(screen.getByRole("button", { name: "Activar Multa por retraso" }));

    expect(handlers.onCreate).toHaveBeenCalledTimes(1);
    expect(handlers.onEdit).toHaveBeenCalledWith(view.rows[0]);
    expect(handlers.onToggle).toHaveBeenCalledWith(view.rows[1]);
  });

  it("offers the six categories and the two states as filters, and reports each choice", async () => {
    const user = userEvent.setup();
    const filters = { category: { value: "", onChange: vi.fn() }, active: { value: "", onChange: vi.fn() } };
    renderWithAdminI18n(<PaymentConceptsView {...props({ filters })} />);
    const group = within(screen.getByRole("group", { name: "Filtros" }));

    expect(within(group.getByLabelText("Categoría")).getAllByRole("option").map((option) => option.textContent)).toEqual([
      "Todos",
      "Colegiatura",
      "Matrícula",
      "Transporte",
      "Materiales",
      "Multa",
      "Otro",
    ]);
    await user.selectOptions(group.getByLabelText("Categoría"), "FINE");
    await user.selectOptions(group.getByLabelText("Estado"), "false");

    expect(filters.category.onChange).toHaveBeenCalledWith("FINE");
    expect(filters.active.onChange).toHaveBeenCalledWith("false");
  });

  it("the loading state shows a busy table, the error state a retry, and the empty state the way forward", () => {
    const { rerender } = renderWithAdminI18n(<PaymentConceptsView {...props({ rows: [], table: { ...loadedTable, loading: true } })} />);
    expect(screen.getByRole("table")).toHaveAttribute("aria-busy", "true");

    rerender(<PaymentConceptsView {...props({ rows: [], table: { ...loadedTable, error: { title: "No se pudo cargar", onRetry: vi.fn() } } })} />);
    expect(screen.getByRole("button", { name: "Reintentar" })).toBeInTheDocument();

    rerender(<PaymentConceptsView {...props({ rows: [] })} />);
    expect(screen.getByText("vacío")).toBeInTheDocument();
  });

  it("tells the result of the last action in its region", () => {
    renderWithAdminI18n(<PaymentConceptsView {...props({ notice: { tone: "success", message: "Se creó el registro «Colegiatura»." } })} />);

    expect(screen.getByText("Se creó el registro «Colegiatura».")).toBeInTheDocument();
  });
});
