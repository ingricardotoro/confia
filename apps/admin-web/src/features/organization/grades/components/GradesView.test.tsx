import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { grade } from "../../../../test/contract-responses.ts";
import { loadedTable, onePage } from "../../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../../test/render.tsx";
import { GradesView, type GradesViewProps } from "./GradesView.tsx";

// States. AW57, AW58: the table of grades rendered with props alone. Loading: `table.loading`. Error and empty:
// `table.error` and `table.empty`, drawn by the table. A partial error: `lookupFailure`, when the modalities of the
// filter did not load. Success: the rows, with the name of the modality, or a neutral text when it is unknown.
const MODALITY_ID = "7f9c24e8-3b1a-4d6e-9f2a-1c5b8a7d3e40";

function props(override: Partial<GradesViewProps> = {}): GradesViewProps {
  return {
    rows: [grade({ name: "Primero", ordinal: 1, modalityId: MODALITY_ID })],
    table: loadedTable,
    pagination: onePage,
    modalityNames: new Map([[MODALITY_ID, "Primaria"]]),
    modalityFilter: { value: "", options: [{ value: MODALITY_ID, label: "Primaria" }], onChange: vi.fn() },
    onCreate: vi.fn(),
    onEdit: vi.fn(),
    onToggle: vi.fn(),
    ...override,
  };
}

describe("GradesView", () => {
  it("AW57: shows the name, the order aligned to the end, the modality by name and the state", () => {
    renderWithAdminI18n(<GradesView {...props()} />);

    expect(screen.getAllByRole("columnheader").map((header) => header.textContent)).toEqual(["Nombre", "Orden", "Modalidad", "Estado", "Acciones"]);
    const row = screen.getByRole("row", { name: /Primero/ });
    expect(within(row).getByText("Primaria")).toBeInTheDocument();
    expect(within(row).getByText("1")).toHaveClass("text-right");
    expect(within(row).getByText("Activo")).toBeInTheDocument();
  });

  it("says the modality is not available when it is not among the names it has", () => {
    renderWithAdminI18n(<GradesView {...props({ modalityNames: new Map() })} />);

    expect(within(screen.getByRole("row", { name: /Primero/ })).getByText("No disponible")).toBeInTheDocument();
  });

  it("filters by modality with a labelled select that starts at All, and reports the choice", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithAdminI18n(<GradesView {...props({ modalityFilter: { value: "", options: [{ value: MODALITY_ID, label: "Primaria" }], onChange } })} />);

    const filter = within(screen.getByRole("group", { name: "Filtros" })).getByLabelText("Modalidad");
    expect(within(filter).getAllByRole("option").map((option) => option.textContent)).toEqual(["Todos", "Primaria"]);
    await user.selectOptions(filter, MODALITY_ID);

    expect(onChange).toHaveBeenCalledWith(MODALITY_ID);
  });

  it("reports the row of the edit and of the toggle, and the creation", async () => {
    const user = userEvent.setup();
    const handlers = { onCreate: vi.fn(), onEdit: vi.fn(), onToggle: vi.fn() };
    const view = props(handlers);
    renderWithAdminI18n(<GradesView {...view} />);

    await user.click(screen.getByRole("button", { name: "Nuevo grado" }));
    await user.click(screen.getByRole("button", { name: "Editar Primero" }));
    await user.click(screen.getByRole("button", { name: "Desactivar Primero" }));

    expect(handlers.onCreate).toHaveBeenCalledTimes(1);
    expect(handlers.onEdit).toHaveBeenCalledWith(view.rows[0]);
    expect(handlers.onToggle).toHaveBeenCalledWith(view.rows[0]);
  });

  it("the loading state shows a busy table, the error state a retry, and the empty state the way forward", () => {
    const { rerender } = renderWithAdminI18n(<GradesView {...props({ rows: [], table: { ...loadedTable, loading: true } })} />);
    expect(screen.getByRole("table")).toHaveAttribute("aria-busy", "true");

    rerender(<GradesView {...props({ rows: [], table: { ...loadedTable, error: { title: "No se pudo cargar", onRetry: vi.fn() } } })} />);
    expect(screen.getByRole("button", { name: "Reintentar" })).toBeInTheDocument();

    rerender(<GradesView {...props({ rows: [] })} />);
    expect(screen.getByText("vacío")).toBeInTheDocument();
  });

  it("the partial error warns that the options did not load and offers a retry, and the list still shows", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderWithAdminI18n(<GradesView {...props({ lookupFailure: { onRetry } })} />);

    expect(screen.getByRole("status")).toHaveTextContent("No se pudieron cargar las opciones");
    expect(screen.getByRole("row", { name: /Primero/ })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(onRetry).toHaveBeenCalledTimes(1);
  });
});
