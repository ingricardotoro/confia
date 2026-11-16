import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { ResourceScreen, type ResourceScreenProps } from "./ResourceScreen.tsx";

// States. AW57, AW58: the screen every view shares, rendered with props alone: no Query provider, no router and
// no request. Loading: `table.loading`. Error: `table.error` (the load failed), `notice` with the error tone (the
// last action failed) and `lookupFailure` (a partial error). Empty: `table.empty`. Success: rows, with the
// pages and a success notice.
interface Row {
  readonly id: string;
  readonly name: string;
}

function props(override: Partial<ResourceScreenProps<Row>> = {}): ResourceScreenProps<Row> {
  return {
    heading: "Años lectivos",
    createLabel: "Nuevo año lectivo",
    onCreate: vi.fn(),
    table: {
      caption: "Años lectivos de la institución",
      columns: [{ id: "name", header: "Nombre", sortable: true, cell: (row: Row) => row.name }],
      rows: [{ id: "1", name: "Año 2026" }],
      getRowId: (row: Row) => row.id,
      empty: { title: "Todavía no hay años lectivos", action: { label: "Crear el primer año lectivo", onAction: vi.fn() } },
      onSortChange: vi.fn(),
    },
    pagination: { page: 0, size: 25, total: 1, busy: false, onPageChange: vi.fn() },
    ...override,
  };
}

describe("ResourceScreen", () => {
  it("AW57: has a level 2 heading, the one primary action, and the table with its rows", async () => {
    const user = userEvent.setup();
    const onCreate = vi.fn();
    renderWithAdminI18n(<ResourceScreen {...props({ onCreate })} />);

    expect(screen.getByRole("heading", { level: 2, name: "Años lectivos" })).toBeInTheDocument();
    expect(screen.getByRole("table", { name: "Años lectivos de la institución" })).toBeInTheDocument();
    expect(screen.getByRole("row", { name: "Año 2026" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Nuevo año lectivo" }));

    expect(onCreate).toHaveBeenCalledTimes(1);
  });

  it("the loading state shows a table that is busy, with no row and no pages", () => {
    const base = props();
    renderWithAdminI18n(
      <ResourceScreen {...props({ table: { ...base.table, loading: true, rows: [] }, pagination: { ...base.pagination, total: 0 } })} />,
    );

    expect(screen.getByRole("table")).toHaveAttribute("aria-busy", "true");
    expect(screen.queryByRole("button", { name: "Siguiente" })).not.toBeInTheDocument();
  });

  it("the error state shows the message and a retry that the caller handles", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    const base = props();
    renderWithAdminI18n(
      <ResourceScreen {...props({ table: { ...base.table, rows: [], error: { title: "No se pudo cargar la lista", onRetry } } })} />,
    );

    expect(screen.getByRole("alert")).toHaveTextContent("No se pudo cargar la lista");
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it("the empty state names the context and offers the way forward", async () => {
    const user = userEvent.setup();
    const onAction = vi.fn();
    const base = props();
    renderWithAdminI18n(
      <ResourceScreen
        {...props({
          table: {
            ...base.table,
            rows: [],
            empty: { title: "Todavía no hay años lectivos", action: { label: "Crear el primer año lectivo", onAction } },
          },
        })}
      />,
    );

    expect(screen.getByText("Todavía no hay años lectivos")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Crear el primer año lectivo" }));

    expect(onAction).toHaveBeenCalledTimes(1);
  });

  it("the region of the result is always in the document and holds the success or the error of the last action", () => {
    const { rerender } = renderWithAdminI18n(<ResourceScreen {...props()} />);
    expect(screen.queryByRole("status")).not.toBeInTheDocument();

    rerender(<ResourceScreen {...props({ notice: { tone: "success", message: "Se creó el registro «2027»." } })} />);
    expect(screen.getByRole("status")).toHaveTextContent("Se creó el registro «2027».");
    // The message is put inside a region that was already in the document, so it is announced.
    expect(screen.getByRole("status").closest("[aria-live='polite']")).not.toBeNull();

    rerender(<ResourceScreen {...props({ notice: { tone: "error", message: "No tiene permiso.", traceId: "abc-123" } })} />);
    expect(screen.getByRole("alert")).toHaveTextContent("No tiene permiso.");
    expect(screen.getByRole("alert")).toHaveTextContent("Identificador para soporte: abc-123");
  });

  it("shows the filters in a labelled group, and a warning with a retry when the lookup lists failed", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderWithAdminI18n(<ResourceScreen {...props({ filters: <button type="button">Un filtro</button>, lookupFailure: { onRetry } })} />);

    expect(within(screen.getByRole("group", { name: "Filtros" })).getByRole("button", { name: "Un filtro" })).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("No se pudieron cargar las opciones");
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it("reports the page asked for, and waits while one is being fetched", async () => {
    const user = userEvent.setup();
    const onPageChange = vi.fn();
    const base = props();
    const { rerender } = renderWithAdminI18n(<ResourceScreen {...props({ pagination: { ...base.pagination, total: 60, onPageChange } })} />);

    await user.click(screen.getByRole("button", { name: "Siguiente" }));
    expect(onPageChange).toHaveBeenCalledWith(1);

    rerender(<ResourceScreen {...props({ pagination: { ...base.pagination, total: 60, onPageChange, busy: true } })} />);
    expect(screen.getByRole("button", { name: "Siguiente" })).toBeDisabled();
  });
});
