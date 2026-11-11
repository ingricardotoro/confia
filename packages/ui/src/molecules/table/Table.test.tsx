import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { renderWithI18n } from "../../test/render";
import { Table, type TableColumn, type TableProps } from "./Table";

interface Row {
  id: string;
  name: string;
  total: string;
}

const COLUMNS: TableColumn<Row>[] = [
  { id: "name", header: "Nombre", cell: (row) => row.name, sortable: true },
  { id: "total", header: "Total", cell: (row) => row.total, align: "end" },
];
const ROWS: Row[] = [
  { id: "1", name: "Regular", total: "12" },
  { id: "2", name: "Nocturna", total: "6" },
];

function renderTable(props: Partial<TableProps<Row>> = {}) {
  return renderWithI18n(
    <Table<Row>
      caption="Modalidades"
      columns={COLUMNS}
      rows={ROWS}
      getRowId={(row) => row.id}
      empty={{ title: "Sin modalidades", action: { label: "Crear modalidad", onAction: vi.fn() } }}
      {...props}
    />,
  );
}

describe("Table", () => {
  it("success: is a table named by its caption, with column headers and one row per item", () => {
    renderTable();

    expect(screen.getByRole("table", { name: "Modalidades" })).toBeVisible();
    expect(screen.getAllByRole("columnheader").map((header) => header.textContent)).toEqual(["Nombre", "Total"]);
    expect(screen.getAllByRole("row")).toHaveLength(3);
    expect(screen.getByRole("cell", { name: "Nocturna" })).toBeVisible();
  });

  it("marks only the ordered column with aria-sort", () => {
    renderTable({ sort: { columnId: "name", direction: "desc" } });

    expect(screen.getByRole("columnheader", { name: "Nombre" })).toHaveAttribute("aria-sort", "descending");
    expect(screen.getByRole("columnheader", { name: "Total" })).not.toHaveAttribute("aria-sort");
  });

  it("reports ascending first, then flips to descending, from the keyboard", async () => {
    const user = userEvent.setup();
    const onSortChange = vi.fn();
    const { rerender } = renderTable({ onSortChange });

    await user.tab();
    expect(screen.getByRole("button", { name: "Nombre" })).toHaveFocus();
    await user.keyboard("{Enter}");
    expect(onSortChange).toHaveBeenLastCalledWith({ columnId: "name", direction: "asc" });

    rerender(
      <Table<Row>
        caption="Modalidades"
        columns={COLUMNS}
        rows={ROWS}
        getRowId={(row) => row.id}
        empty={{ title: "Sin modalidades", action: { label: "Crear modalidad", onAction: vi.fn() } }}
        sort={{ columnId: "name", direction: "asc" }}
        onSortChange={onSortChange}
      />,
    );
    await user.keyboard(" ");
    expect(onSortChange).toHaveBeenLastCalledWith({ columnId: "name", direction: "desc" });
  });

  it("renders no sort button for a column that is not sortable", () => {
    renderTable();

    expect(screen.queryByRole("button", { name: "Total" })).not.toBeInTheDocument();
  });

  it("loading: shows skeleton rows with the shape of the content, announces it and hides rows", () => {
    renderTable({ loading: true, skeletonRows: 3 });

    expect(screen.getByRole("status")).toHaveTextContent("Cargando");
    expect(screen.getByRole("table", { name: "Modalidades" })).toHaveAttribute("aria-busy", "true");
    expect(screen.getAllByRole("row")).toHaveLength(4);
    expect(screen.queryByText("Regular")).not.toBeInTheDocument();
  });

  it("empty: offers the action that resolves it and is not an alert", async () => {
    const user = userEvent.setup();
    const onAction = vi.fn();
    renderTable({ rows: [], empty: { title: "Sin modalidades", action: { label: "Crear modalidad", onAction } } });

    expect(screen.getByText("Sin modalidades")).toBeVisible();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Crear modalidad" }));
    expect(onAction).toHaveBeenCalledTimes(1);
  });

  it("error: announces the failure and retries, and does not show the empty state", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderTable({ rows: [], error: { title: "No pudimos cargar", onRetry } });

    expect(within(screen.getByRole("alert")).getByText("No pudimos cargar")).toBeVisible();
    expect(screen.queryByText("Sin modalidades")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });
});
