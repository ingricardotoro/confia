import { screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { acta } from "../../../shared/api/reconciliation-fixtures.ts";
import { loadedTable, onePage } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { ReconciliationHistory, type ReconciliationHistoryProps } from "./ReconciliationHistory.tsx";

// States. AW57, AW58: the actas signed, with props alone. Loading: `table.loading`; error and empty: `table.error` and `table.empty`, drawn by the
// table with their action; success: one row per acta with the year by name and the difference in words.
const YEAR_ID = "11111111-1111-4111-8111-111111111111";
const SIGNER = "99999999-9999-4999-8999-999999999999";

function props(override: Partial<ReconciliationHistoryProps> = {}): ReconciliationHistoryProps {
  return {
    rows: [
      acta({ officialReference: "Oficio 0123-2026", officialEnrollmentCount: 120, enrollmentsActive: 118, difference: -2, signedBy: SIGNER }),
      acta({ officialReference: "Oficio 0099-2026", officialEnrollmentCount: 100, enrollmentsActive: 105, difference: 5 }),
      acta({ officialReference: "Oficio 0001-2026", officialEnrollmentCount: 118, enrollmentsActive: 118, difference: 0 }),
    ],
    table: loadedTable,
    pagination: { ...onePage, total: 3 },
    yearNames: new Map([[YEAR_ID, "Año lectivo 2026"]]),
    ...override,
  };
}

describe("ReconciliationHistory", () => {
  it("lists each acta with the year by name, its counts aligned to the end and the difference in words", () => {
    renderWithAdminI18n(<ReconciliationHistory {...props()} />);

    const table = screen.getByRole("table", { name: /Actas de conciliación firmadas/ });
    expect(within(table).getAllByRole("columnheader").map((header) => header.textContent)).toEqual([
      "Firmada",
      "Año lectivo",
      "Referencia",
      "Total oficial",
      "Matrículas activas al firmar",
      "Diferencia",
    ]);
    const rows = within(table)
      .getAllByRole("row")
      .slice(1)
      .map((row) => within(row).getAllByRole("cell").map((cell) => cell.textContent));
    expect(rows.map((cells) => cells.slice(1))).toEqual([
      ["Año lectivo 2026", "Oficio 0123-2026", "120", "118", "2 menos"],
      ["Año lectivo 2026", "Oficio 0099-2026", "100", "105", "5 más"],
      ["Año lectivo 2026", "Oficio 0001-2026", "118", "118", "Coincide"],
    ]);
    expect(rows[0]?.[0]).toMatch(/2026/);
  });

  it("never shows the account that signed", () => {
    renderWithAdminI18n(<ReconciliationHistory {...props()} />);

    expect(document.body.textContent).not.toContain(SIGNER);
  });

  it("names a year it does not know with a neutral label", () => {
    renderWithAdminI18n(<ReconciliationHistory {...props({ yearNames: new Map() })} />);

    expect(screen.getAllByText("Año lectivo no disponible")).toHaveLength(3);
  });

  it("shows the loading state as a skeleton with the shape of the rows", () => {
    renderWithAdminI18n(<ReconciliationHistory {...props({ rows: [], table: { ...loadedTable, loading: true } })} />);

    expect(screen.getByRole("table", { name: /Actas de conciliación firmadas/ })).toHaveAttribute("aria-busy", "true");
  });

  it("shows the empty state with the way forward", () => {
    renderWithAdminI18n(
      <ReconciliationHistory
        {...props({
          rows: [],
          pagination: { ...onePage, total: 0 },
          table: { empty: { title: "Aún no hay actas firmadas", description: "Cuando firme", action: { label: "Ir al formulario del acta", onAction: () => undefined } } },
        })}
      />,
    );

    expect(screen.getByText("Aún no hay actas firmadas")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Ir al formulario del acta" })).toBeInTheDocument();
  });

  it("shows the error with an action to try again", () => {
    renderWithAdminI18n(
      <ReconciliationHistory
        {...props({ rows: [], table: { ...loadedTable, error: { title: "No se pudieron cargar las actas firmadas", onRetry: () => undefined } } })}
      />,
    );

    expect(screen.getByRole("alert")).toHaveTextContent("No se pudieron cargar las actas firmadas");
    expect(screen.getByRole("button", { name: "Reintentar" })).toBeInTheDocument();
  });
});
