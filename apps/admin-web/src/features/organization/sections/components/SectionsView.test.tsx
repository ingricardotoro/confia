import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { section } from "../../../../test/contract-responses.ts";
import { loadedTable, onePage } from "../../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../../test/render.tsx";
import { SectionsView, type SectionsViewProps } from "./SectionsView.tsx";

// States. AW57, AW58: the table of sections rendered with props alone. Loading: `table.loading`. Error and empty:
// `table.error` and `table.empty`, drawn by the table. A partial error: `lookupFailure`, when the options did not
// load. Success: the rows, with the year and the grade by name, and a section with no capacity.
const YEAR_ID = "7f9c24e8-3b1a-4d6e-9f2a-1c5b8a7d3e40";
const GRADE_ID = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";

function props(override: Partial<SectionsViewProps> = {}): SectionsViewProps {
  return {
    rows: [
      section({ name: "A", academicYearId: YEAR_ID, gradeId: GRADE_ID, capacity: 30 }),
      section({ name: "B", academicYearId: YEAR_ID, gradeId: GRADE_ID, capacity: null, isActive: false }),
    ],
    table: loadedTable,
    pagination: onePage,
    yearNames: new Map([[YEAR_ID, "Año 2026"]]),
    gradeNames: new Map([[GRADE_ID, "Primaria · Primero"]]),
    yearFilter: { value: "", options: [{ value: YEAR_ID, label: "Año 2026" }], onChange: vi.fn() },
    gradeFilter: { value: "", options: [{ value: GRADE_ID, label: "Primaria · Primero" }], onChange: vi.fn() },
    onCreate: vi.fn(),
    onEdit: vi.fn(),
    onToggle: vi.fn(),
    ...override,
  };
}

describe("SectionsView", () => {
  it("AW57: shows the year and the grade by name, the capacity aligned to the end and the state", () => {
    renderWithAdminI18n(<SectionsView {...props()} />);

    expect(screen.getAllByRole("columnheader").map((header) => header.textContent)).toEqual([
      "Nombre",
      "Año lectivo",
      "Grado",
      "Cupo",
      "Estado",
      "Acciones",
    ]);
    const first = screen.getByRole("row", { name: /^A / });
    expect(within(first).getByText("Año 2026")).toBeInTheDocument();
    expect(within(first).getByText("Primaria · Primero")).toBeInTheDocument();
    expect(within(first).getByText("30")).toHaveClass("text-right");
    const second = screen.getByRole("row", { name: /^B / });
    expect(within(second).getByText("Sin cupo definido")).toBeInTheDocument();
    expect(within(second).getByText("Inactivo")).toBeInTheDocument();
  });

  it("says a year or a grade is not available when it is not among the names it has", () => {
    renderWithAdminI18n(<SectionsView {...props({ yearNames: new Map(), gradeNames: new Map() })} />);

    expect(within(screen.getByRole("row", { name: /^A / })).getAllByText("No disponible")).toHaveLength(2);
  });

  it("filters by year and by grade with two labelled selects that start at All, and reports each choice", async () => {
    const user = userEvent.setup();
    const yearFilter = { value: "", options: [{ value: YEAR_ID, label: "Año 2026" }], onChange: vi.fn() };
    const gradeFilter = { value: "", options: [{ value: GRADE_ID, label: "Primaria · Primero" }], onChange: vi.fn() };
    renderWithAdminI18n(<SectionsView {...props({ yearFilter, gradeFilter })} />);

    const filters = within(screen.getByRole("group", { name: "Filtros" }));
    expect(within(filters.getByLabelText("Año lectivo")).getAllByRole("option").map((option) => option.textContent)).toEqual(["Todos", "Año 2026"]);
    await user.selectOptions(filters.getByLabelText("Año lectivo"), YEAR_ID);
    await user.selectOptions(filters.getByLabelText("Grado"), GRADE_ID);

    expect(yearFilter.onChange).toHaveBeenCalledWith(YEAR_ID);
    expect(gradeFilter.onChange).toHaveBeenCalledWith(GRADE_ID);
  });

  it("reports the row of the edit and of the toggle, and the creation", async () => {
    const user = userEvent.setup();
    const handlers = { onCreate: vi.fn(), onEdit: vi.fn(), onToggle: vi.fn() };
    const view = props(handlers);
    renderWithAdminI18n(<SectionsView {...view} />);

    await user.click(screen.getByRole("button", { name: "Nueva sección" }));
    await user.click(screen.getByRole("button", { name: "Editar A" }));
    await user.click(screen.getByRole("button", { name: "Activar B" }));

    expect(handlers.onCreate).toHaveBeenCalledTimes(1);
    expect(handlers.onEdit).toHaveBeenCalledWith(view.rows[0]);
    expect(handlers.onToggle).toHaveBeenCalledWith(view.rows[1]);
  });

  it("the loading state shows a busy table, the error state a retry, and the empty state the way forward", () => {
    const { rerender } = renderWithAdminI18n(<SectionsView {...props({ rows: [], table: { ...loadedTable, loading: true } })} />);
    expect(screen.getByRole("table")).toHaveAttribute("aria-busy", "true");

    rerender(<SectionsView {...props({ rows: [], table: { ...loadedTable, error: { title: "No se pudo cargar", onRetry: vi.fn() } } })} />);
    expect(screen.getByRole("button", { name: "Reintentar" })).toBeInTheDocument();

    rerender(<SectionsView {...props({ rows: [] })} />);
    expect(screen.getByText("vacío")).toBeInTheDocument();
  });

  it("the partial error warns that the options did not load and offers a retry, and the list still shows", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderWithAdminI18n(<SectionsView {...props({ lookupFailure: { onRetry } })} />);

    expect(screen.getByRole("status")).toHaveTextContent("No se pudieron cargar las opciones");
    expect(screen.getByRole("row", { name: /^A / })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(onRetry).toHaveBeenCalledTimes(1);
  });
});
