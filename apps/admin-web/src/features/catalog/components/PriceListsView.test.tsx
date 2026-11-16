import { fireEvent, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { priceList } from "../../../test/contract-responses.ts";
import { loadedTable, onePage } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { PriceListsView, type PriceListsViewProps } from "./PriceListsView.tsx";

// States. AW57, AW58: the table of price lists rendered with props alone. Loading: `table.loading`. Error and empty:
// `table.error` and `table.empty`, drawn by the table in place of its body. Success: one list for the whole
// institution and one archived list of a grade.
const YEAR = "00000000-0000-4000-8000-000000000a01";
const GRADE = "00000000-0000-4000-8000-000000000c01";

function props(override: Partial<PriceListsViewProps> = {}): PriceListsViewProps {
  return {
    rows: [
      priceList({ id: "00000000-0000-4000-8000-0000000000e1", name: "2027 — Institución", academicYearId: YEAR }),
      priceList({ id: "00000000-0000-4000-8000-0000000000e2", name: "2027 — Primero", academicYearId: YEAR, scope: "GRADE", scopeRefId: GRADE, status: "ARCHIVED" }),
    ],
    table: loadedTable,
    pagination: onePage,
    yearNames: new Map([[YEAR, "Año 2027"]]),
    referenceNames: new Map([[GRADE, "Primer grado"]]),
    yearOptions: [{ value: YEAR, label: "Año 2027" }],
    filters: {
      year: { value: "", onChange: vi.fn() },
      scope: { value: "", onChange: vi.fn() },
      status: { value: "", onChange: vi.fn() },
    },
    hrefOf: (row) => `/catalog/price-lists/${row.id}`,
    onOpen: vi.fn(),
    onCreate: vi.fn(),
    ...override,
  };
}

describe("PriceListsView", () => {
  it("shows the name, year, scope, reference and state of each list, in text", () => {
    renderWithAdminI18n(<PriceListsView {...props()} />);

    expect(screen.getAllByRole("columnheader").map((header) => header.textContent)).toEqual(["Nombre", "Año lectivo", "Ámbito", "Modalidad o grado", "Estado"]);
    const first = within(screen.getByRole("row", { name: /2027 — Institución/ }));
    expect(first.getByText("Año 2027")).toBeInTheDocument();
    expect(first.getByText("Toda la institución")).toBeInTheDocument();
    expect(first.getByText("No aplica")).toBeInTheDocument();
    expect(first.getByText("Activa")).toBeInTheDocument();
    const second = within(screen.getByRole("row", { name: /2027 — Primero/ }));
    expect(second.getByText("Un grado")).toBeInTheDocument();
    expect(second.getByText("Primer grado")).toBeInTheDocument();
    expect(second.getByText("Archivada")).toBeInTheDocument();
  });

  it("says a year or a reference is not available when its name was not loaded", () => {
    renderWithAdminI18n(<PriceListsView {...props({ yearNames: new Map(), referenceNames: new Map() })} />);

    expect(within(screen.getByRole("row", { name: /2027 — Primero/ })).getAllByText("No disponible")).toHaveLength(2);
  });

  it("makes the name a link to the detail that the router follows on a plain click only", () => {
    const onOpen = vi.fn();
    const view = props({ onOpen });
    renderWithAdminI18n(<PriceListsView {...view} />);
    const link = screen.getByRole("link", { name: "2027 — Primero" });

    expect(link).toHaveAttribute("href", "/catalog/price-lists/00000000-0000-4000-8000-0000000000e2");
    fireEvent.click(link, { ctrlKey: true });
    expect(onOpen).not.toHaveBeenCalled();
    fireEvent.click(link);
    expect(onOpen).toHaveBeenCalledWith(view.rows[1]);
  });

  it("offers the year, the three scopes and the two states as filters, and reports each choice", async () => {
    const user = userEvent.setup();
    const filters = { year: { value: "", onChange: vi.fn() }, scope: { value: "", onChange: vi.fn() }, status: { value: "", onChange: vi.fn() } };
    renderWithAdminI18n(<PriceListsView {...props({ filters })} />);
    const group = within(screen.getByRole("group", { name: "Filtros" }));

    expect(within(group.getByLabelText("Ámbito")).getAllByRole("option").map((option) => option.textContent)).toEqual([
      "Todos",
      "Toda la institución",
      "Una modalidad",
      "Un grado",
    ]);
    await user.selectOptions(group.getByLabelText("Año lectivo"), YEAR);
    await user.selectOptions(group.getByLabelText("Ámbito"), "GRADE");
    await user.selectOptions(group.getByLabelText("Estado"), "ARCHIVED");

    expect(filters.year.onChange).toHaveBeenCalledWith(YEAR);
    expect(filters.scope.onChange).toHaveBeenCalledWith("GRADE");
    expect(filters.status.onChange).toHaveBeenCalledWith("ARCHIVED");
  });

  it("reports the creation", async () => {
    const user = userEvent.setup();
    const onCreate = vi.fn();
    renderWithAdminI18n(<PriceListsView {...props({ onCreate })} />);

    await user.click(screen.getByRole("button", { name: "Nueva lista de precios" }));

    expect(onCreate).toHaveBeenCalledTimes(1);
  });

  it("the loading state shows a busy table, the error state a retry, and the empty state the way forward", () => {
    const { rerender } = renderWithAdminI18n(<PriceListsView {...props({ rows: [], table: { ...loadedTable, loading: true } })} />);
    expect(screen.getByRole("table")).toHaveAttribute("aria-busy", "true");

    rerender(<PriceListsView {...props({ rows: [], table: { ...loadedTable, error: { title: "No se pudo cargar", onRetry: vi.fn() } } })} />);
    expect(screen.getByRole("button", { name: "Reintentar" })).toBeInTheDocument();

    rerender(<PriceListsView {...props({ rows: [] })} />);
    expect(screen.getByText("vacío")).toBeInTheDocument();
  });

  it("warns when the options of the filters failed to load, and tells the result of the last action", () => {
    const onRetry = vi.fn();
    renderWithAdminI18n(<PriceListsView {...props({ lookupFailure: { onRetry }, notice: { tone: "success", message: "Se creó el registro «2027»." } })} />);

    expect(screen.getByText("No se pudieron cargar las opciones de los filtros y de los formularios.")).toBeInTheDocument();
    expect(screen.getByText("Se creó el registro «2027».")).toBeInTheDocument();
  });
});
