import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { academicYear } from "../../../../test/contract-responses.ts";
import { loadedTable, onePage } from "../../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../../test/render.tsx";
import { AcademicYearsView, type AcademicYearsViewProps } from "./AcademicYearsView.tsx";

// States. AW57, AW58: the table of academic years rendered with props alone. Loading: `table.loading` shows
// skeleton rows with the same columns. Error and empty: `table.error` and `table.empty`, which the table draws in
// place of its body. Success: the rows, with a year that is closed and one that is inactive.
function props(override: Partial<AcademicYearsViewProps> = {}): AcademicYearsViewProps {
  return {
    rows: [academicYear({ name: "Año 2026", startDate: "2026-01-15", endDate: "2026-11-30", status: "ACTIVE" })],
    table: loadedTable,
    pagination: onePage,
    onCreate: vi.fn(),
    onEdit: vi.fn(),
    onToggle: vi.fn(),
    ...override,
  };
}

describe("AcademicYearsView", () => {
  it("AW57: shows the columns, the dates written for the person, the stage and the state", () => {
    renderWithAdminI18n(<AcademicYearsView {...props()} />);

    expect(screen.getAllByRole("columnheader").map((header) => header.textContent)).toEqual([
      "Nombre",
      "Inicio",
      "Fin",
      "Etapa",
      "Estado",
      "Acciones",
    ]);
    const row = screen.getByRole("row", { name: /Año 2026/ });
    // The date is the day the server sent, whatever the zone of the browser.
    expect(within(row).getByText(/15.*2026/)).toBeInTheDocument();
    expect(within(row).getByText(/30.*2026/)).toBeInTheDocument();
    expect(within(row).getByText("En curso")).toBeInTheDocument();
    expect(within(row).getByText("Activo")).toBeInTheDocument();
  });

  it("only the date columns, the name and the stage are sortable", () => {
    renderWithAdminI18n(<AcademicYearsView {...props()} />);

    expect(screen.getAllByRole("button", { name: /^(Nombre|Inicio|Fin|Etapa)$/ })).toHaveLength(4);
  });

  it("reports the row of the edit and of the toggle, and the creation", async () => {
    const user = userEvent.setup();
    const handlers = { onCreate: vi.fn(), onEdit: vi.fn(), onToggle: vi.fn() };
    const view = props(handlers);
    renderWithAdminI18n(<AcademicYearsView {...view} />);

    await user.click(screen.getByRole("button", { name: "Nuevo año lectivo" }));
    await user.click(screen.getByRole("button", { name: "Editar Año 2026" }));
    await user.click(screen.getByRole("button", { name: "Desactivar Año 2026" }));

    expect(handlers.onCreate).toHaveBeenCalledTimes(1);
    expect(handlers.onEdit).toHaveBeenCalledWith(view.rows[0]);
    expect(handlers.onToggle).toHaveBeenCalledWith(view.rows[0]);
  });

  it("offers to activate a year that is inactive, and marks only the toggling row as busy", () => {
    const inactive = academicYear({ name: "Año 2025", status: "CLOSED", isActive: false });
    renderWithAdminI18n(<AcademicYearsView {...props({ rows: [inactive, academicYear({ name: "Año 2026" })], togglingId: inactive.id })} />);

    expect(screen.getByRole("button", { name: "Activar Año 2025" })).toHaveAttribute("aria-busy", "true");
    expect(screen.getByRole("button", { name: "Desactivar Año 2026" })).not.toHaveAttribute("aria-busy");
  });

  it("the loading state shows a busy table, the error state a retry, and the empty state the way forward", () => {
    const { rerender } = renderWithAdminI18n(<AcademicYearsView {...props({ rows: [], table: { ...loadedTable, loading: true } })} />);
    expect(screen.getByRole("table")).toHaveAttribute("aria-busy", "true");

    rerender(<AcademicYearsView {...props({ rows: [], table: { ...loadedTable, error: { title: "No se pudo cargar", onRetry: vi.fn() } } })} />);
    expect(screen.getByRole("button", { name: "Reintentar" })).toBeInTheDocument();

    rerender(<AcademicYearsView {...props({ rows: [] })} />);
    expect(screen.getByText("vacío")).toBeInTheDocument();
  });
});
