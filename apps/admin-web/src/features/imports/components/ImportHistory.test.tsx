import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { importBatch } from "../../../test/contract-responses.ts";
import { loadedTable, onePage } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { ImportHistory, type ImportHistoryProps } from "./ImportHistory.tsx";

// States. AW57, AW58: the history of batches rendered with props alone. Loading: `table.loading`, a skeleton with the shape of the
// rows. Error and empty: `table.error` and `table.empty`, drawn by the table with their action. Success: the rows, one per batch.
function props(override: Partial<ImportHistoryProps> = {}): ImportHistoryProps {
  return {
    rows: [
      importBatch({ status: "APPLIED", totalRows: 100, validRows: 95, rejectedRows: 3, skippedRows: 2, createdRows: 95, appliedAt: "2026-11-10T16:00:00Z" }),
      importBatch({ status: "VALIDATED", totalRows: 100, validRows: 95, rejectedRows: 3, skippedRows: 2 }),
    ],
    table: loadedTable,
    pagination: { ...onePage, total: 2 },
    onKindChange: vi.fn(),
    ...override,
  };
}

describe("ImportHistory", () => {
  it("lists each batch with its state in words and its counts aligned to the end, and never a name", () => {
    renderWithAdminI18n(<ImportHistory {...props()} />);

    const table = screen.getByRole("table", { name: /Importaciones de la institución/ });
    expect(within(table).getAllByRole("columnheader").map((header) => header.textContent)).toEqual([
      "Fecha",
      "Tipo",
      "Estado",
      "Filas",
      "Creados",
      "Rechazadas",
      "Omitidas",
    ]);
    const [applied, simulated] = within(table).getAllByRole("row").slice(1);
    expect(within(applied as HTMLElement).getByText("Aplicada")).toBeInTheDocument();
    expect(within(applied as HTMLElement).getAllByRole("cell").map((cell) => cell.textContent).slice(3)).toEqual(["100", "95", "3", "2"]);
    expect(within(simulated as HTMLElement).getByText("Simulada")).toBeInTheDocument();
  });

  it("says the kind of each batch in words", () => {
    renderWithAdminI18n(
      <ImportHistory
        {...props({ rows: [importBatch({ kind: "STUDENTS" }), importBatch({ kind: "GUARDIANS" }), importBatch({ kind: "GUARDIAN_LINKS" }), importBatch({ kind: "ENROLLMENTS" })], pagination: { ...onePage, total: 4 } })}
      />,
    );

    const rows = within(screen.getByRole("table")).getAllByRole("row").slice(1);
    expect(rows.map((row) => within(row).getAllByRole("cell")[1]?.textContent)).toEqual(["Estudiantes", "Encargados", "Vínculos", "Matrículas"]);
  });

  it("filters by kind: every kind first, the four kinds after it, and it only reports the choice", async () => {
    const onKindChange = vi.fn();
    renderWithAdminI18n(<ImportHistory {...props({ onKindChange, kind: "guardians" })} />);
    const filter = screen.getByLabelText("Tipo de importación");

    expect(within(filter).getAllByRole("option").map((option) => option.textContent)).toEqual(["Todos los tipos", "Estudiantes", "Encargados", "Vínculos", "Matrículas"]);
    expect(filter).toHaveValue("guardians");
    const user = userEvent.setup();
    await user.selectOptions(filter, "guardian-links");
    await user.selectOptions(filter, "enrollments");
    await user.selectOptions(filter, "");

    expect(onKindChange).toHaveBeenNthCalledWith(1, "guardian-links");
    expect(onKindChange).toHaveBeenNthCalledWith(2, "enrollments");
    expect(onKindChange).toHaveBeenNthCalledWith(3, undefined);
  });

  it("shows the empty state with the way forward, and runs it", async () => {
    const onAction = vi.fn();
    renderWithAdminI18n(
      <ImportHistory
        {...props({
          rows: [],
          table: { empty: { title: "Todavía no hay importaciones", description: "Aquí aparecerá cada una.", action: { label: "Elegir un archivo", onAction } } },
          pagination: { ...onePage, total: 0 },
        })}
      />,
    );

    await userEvent.setup().click(screen.getByRole("button", { name: "Elegir un archivo" }));

    expect(screen.getByText("Todavía no hay importaciones")).toBeInTheDocument();
    expect(onAction).toHaveBeenCalledTimes(1);
  });

  it("shows the failed load with a retry", async () => {
    const onRetry = vi.fn();
    renderWithAdminI18n(
      <ImportHistory {...props({ rows: [], table: { ...loadedTable, error: { title: "No se pudo cargar el historial", description: "Falló.", onRetry } } })} />,
    );

    await userEvent.setup().click(screen.getByRole("button", { name: "Reintentar" }));

    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it("asks the server for another page and only reports the click", async () => {
    const onPageChange = vi.fn();
    renderWithAdminI18n(<ImportHistory {...props({ pagination: { page: 0, size: 25, total: 60, busy: false, onPageChange } })} />);

    await userEvent.setup().click(screen.getByRole("button", { name: /Siguiente/ }));

    expect(onPageChange).toHaveBeenCalledWith(1);
  });

  it("is a skeleton while it loads", () => {
    renderWithAdminI18n(<ImportHistory {...props({ rows: [], table: { ...loadedTable, loading: true } })} />);

    expect(screen.getByRole("table")).toHaveAttribute("aria-busy", "true");
  });
});
