import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { modality } from "../../../../test/contract-responses.ts";
import { loadedTable, onePage } from "../../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../../test/render.tsx";
import { ModalitiesView, type ModalitiesViewProps } from "./ModalitiesView.tsx";

// States. AW57, AW58: the table of modalities rendered with props alone. Loading: `table.loading`. Error and
// empty: `table.error` and `table.empty`, drawn by the table in place of its body. Success: the rows, one
// active and one inactive.
function props(override: Partial<ModalitiesViewProps> = {}): ModalitiesViewProps {
  return {
    rows: [modality({ code: "PRI", name: "Primaria" }), modality({ code: "BAC", name: "Bachillerato", isActive: false })],
    table: loadedTable,
    pagination: onePage,
    onCreate: vi.fn(),
    onEdit: vi.fn(),
    onToggle: vi.fn(),
    ...override,
  };
}

describe("ModalitiesView", () => {
  it("AW57: shows the code, the name and the state of each modality, in text", () => {
    renderWithAdminI18n(<ModalitiesView {...props()} />);

    expect(screen.getAllByRole("columnheader").map((header) => header.textContent)).toEqual(["Código", "Nombre", "Estado", "Acciones"]);
    expect(within(screen.getByRole("row", { name: /Primaria/ })).getByText("PRI")).toBeInTheDocument();
    expect(within(screen.getByRole("row", { name: /Primaria/ })).getByText("Activo")).toBeInTheDocument();
    expect(within(screen.getByRole("row", { name: /Bachillerato/ })).getByText("Inactivo")).toBeInTheDocument();
  });

  it("reports the row of the edit and of the toggle, and the creation", async () => {
    const user = userEvent.setup();
    const handlers = { onCreate: vi.fn(), onEdit: vi.fn(), onToggle: vi.fn() };
    const view = props(handlers);
    renderWithAdminI18n(<ModalitiesView {...view} />);

    await user.click(screen.getByRole("button", { name: "Nueva modalidad" }));
    await user.click(screen.getByRole("button", { name: "Editar Primaria" }));
    await user.click(screen.getByRole("button", { name: "Activar Bachillerato" }));

    expect(handlers.onCreate).toHaveBeenCalledTimes(1);
    expect(handlers.onEdit).toHaveBeenCalledWith(view.rows[0]);
    expect(handlers.onToggle).toHaveBeenCalledWith(view.rows[1]);
  });

  it("the loading state shows a busy table, the error state a retry, and the empty state the way forward", () => {
    const { rerender } = renderWithAdminI18n(<ModalitiesView {...props({ rows: [], table: { ...loadedTable, loading: true } })} />);
    expect(screen.getByRole("table")).toHaveAttribute("aria-busy", "true");

    rerender(<ModalitiesView {...props({ rows: [], table: { ...loadedTable, error: { title: "No se pudo cargar", onRetry: vi.fn() } } })} />);
    expect(screen.getByRole("button", { name: "Reintentar" })).toBeInTheDocument();

    rerender(<ModalitiesView {...props({ rows: [] })} />);
    expect(screen.getByText("vacío")).toBeInTheDocument();
  });
});
