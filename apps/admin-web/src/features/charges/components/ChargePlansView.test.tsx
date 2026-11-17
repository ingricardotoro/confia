import { fireEvent, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { chargePlan } from "../../../test/contract-responses.ts";
import { loadedTable, onePage } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { ChargePlansView, type ChargePlansViewProps } from "./ChargePlansView.tsx";

// States. AW57, AW58: the table of plans rendered with props alone. Loading: `table.loading`. Error and empty: `table.error`
// and `table.empty`, drawn by the table in place of its body. Success: an active plan of a grade and an archived one of a section.
const YEAR = "00000000-0000-4000-8000-000000000a01";
const GRADE = "00000000-0000-4000-8000-000000000c01";
const SECTION = "00000000-0000-4000-8000-000000000d01";

function props(override: Partial<ChargePlansViewProps> = {}): ChargePlansViewProps {
  return {
    rows: [
      chargePlan({ id: "00000000-0000-4000-8000-0000000000e1", name: "Primaria 2027", academicYearId: YEAR, targetScope: "GRADE", targetRefId: GRADE, status: "ACTIVE", dueDayOfMonth: 5 }),
      chargePlan({
        id: "00000000-0000-4000-8000-0000000000e2",
        name: "Sección A 2027",
        academicYearId: YEAR,
        targetScope: "SECTION",
        targetRefId: SECTION,
        frequency: "ONE_TIME",
        status: "ARCHIVED",
        dueDayOfMonth: 15,
      }),
    ],
    table: loadedTable,
    pagination: onePage,
    yearNames: new Map([[YEAR, "Año 2027"]]),
    referenceNames: new Map([
      [GRADE, "Primer grado"],
      [SECTION, "Primero A (Año 2027)"],
    ]),
    yearOptions: [{ value: YEAR, label: "Año 2027" }],
    filters: {
      year: { value: "", onChange: vi.fn() },
      scope: { value: "", onChange: vi.fn() },
      status: { value: "", onChange: vi.fn() },
    },
    hrefOf: (row) => `/charges/plans/${row.id}`,
    onOpen: vi.fn(),
    onCreate: vi.fn(),
    ...override,
  };
}

describe("ChargePlansView", () => {
  it("shows the name, year, scope, target, frequency, due day and state of each plan, in text", () => {
    renderWithAdminI18n(<ChargePlansView {...props()} />);

    expect(screen.getAllByRole("columnheader").map((header) => header.textContent)).toEqual([
      "Nombre",
      "Año lectivo",
      "Ámbito",
      "Modalidad, grado o sección",
      "Periodicidad",
      "Vencimiento",
      "Estado",
    ]);
    const first = within(screen.getByRole("row", { name: /Primaria 2027/ }));
    expect(first.getByText("Año 2027")).toBeInTheDocument();
    expect(first.getByText("Un grado")).toBeInTheDocument();
    expect(first.getByText("Primer grado")).toBeInTheDocument();
    expect(first.getByText("Mensual")).toBeInTheDocument();
    expect(first.getByText("Día 5")).toBeInTheDocument();
    expect(first.getByText("Activo")).toBeInTheDocument();
    const second = within(screen.getByRole("row", { name: /Sección A 2027/ }));
    expect(second.getByText("Una sección")).toBeInTheDocument();
    expect(second.getByText("Primero A (Año 2027)")).toBeInTheDocument();
    expect(second.getByText("Única vez")).toBeInTheDocument();
    expect(second.getByText("Archivado")).toBeInTheDocument();
  });

  it("says a year or a target is not available when its name was not loaded", () => {
    renderWithAdminI18n(<ChargePlansView {...props({ yearNames: new Map(), referenceNames: new Map() })} />);

    expect(within(screen.getByRole("row", { name: /Primaria 2027/ })).getAllByText("No disponible")).toHaveLength(2);
  });

  it("makes the name a link to the detail that the router follows on a plain click only", () => {
    const onOpen = vi.fn();
    const view = props({ onOpen });
    renderWithAdminI18n(<ChargePlansView {...view} />);
    const link = screen.getByRole("link", { name: "Sección A 2027" });

    expect(link).toHaveAttribute("href", "/charges/plans/00000000-0000-4000-8000-0000000000e2");
    fireEvent.click(link, { ctrlKey: true });
    expect(onOpen).not.toHaveBeenCalled();
    fireEvent.click(link);
    expect(onOpen).toHaveBeenCalledWith(view.rows[1]);
  });

  it("offers the year, the three scopes and the three states as filters, and reports each choice", async () => {
    const user = userEvent.setup();
    const filters = { year: { value: "", onChange: vi.fn() }, scope: { value: "", onChange: vi.fn() }, status: { value: "", onChange: vi.fn() } };
    renderWithAdminI18n(<ChargePlansView {...props({ filters })} />);
    const group = within(screen.getByRole("group", { name: "Filtros" }));

    expect(within(group.getByLabelText("Ámbito")).getAllByRole("option").map((option) => option.textContent)).toEqual(["Todos", "Una modalidad", "Un grado", "Una sección"]);
    expect(within(group.getByLabelText("Estado")).getAllByRole("option").map((option) => option.textContent)).toEqual(["Todos", "Borrador", "Activo", "Archivado"]);
    await user.selectOptions(group.getByLabelText("Año lectivo"), YEAR);
    await user.selectOptions(group.getByLabelText("Ámbito"), "SECTION");
    await user.selectOptions(group.getByLabelText("Estado"), "DRAFT");

    expect(filters.year.onChange).toHaveBeenCalledWith(YEAR);
    expect(filters.scope.onChange).toHaveBeenCalledWith("SECTION");
    expect(filters.status.onChange).toHaveBeenCalledWith("DRAFT");
  });

  it("reports the creation", async () => {
    const user = userEvent.setup();
    const onCreate = vi.fn();
    renderWithAdminI18n(<ChargePlansView {...props({ onCreate })} />);

    await user.click(screen.getByRole("button", { name: "Nuevo plan de cobro" }));

    expect(onCreate).toHaveBeenCalledTimes(1);
  });

  it("the loading state shows a busy table, the error state a retry, and the empty state the way forward", () => {
    const { rerender } = renderWithAdminI18n(<ChargePlansView {...props({ rows: [], table: { ...loadedTable, loading: true } })} />);
    expect(screen.getByRole("table")).toHaveAttribute("aria-busy", "true");

    rerender(<ChargePlansView {...props({ rows: [], table: { ...loadedTable, error: { title: "No se pudo cargar", onRetry: vi.fn() } } })} />);
    expect(screen.getByRole("button", { name: "Reintentar" })).toBeInTheDocument();

    rerender(<ChargePlansView {...props({ rows: [] })} />);
    expect(screen.getByText("vacío")).toBeInTheDocument();
  });

  it("warns when the options of the filters failed to load, and tells the result of the last action", () => {
    renderWithAdminI18n(<ChargePlansView {...props({ lookupFailure: { onRetry: vi.fn() }, notice: { tone: "success", message: "Se creó el registro «Primaria 2027»." } })} />);

    expect(screen.getByText("No se pudieron cargar las opciones de los filtros y de los formularios.")).toBeInTheDocument();
    expect(screen.getByText("Se creó el registro «Primaria 2027».")).toBeInTheDocument();
  });
});
