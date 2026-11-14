import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { preview } from "../../../shared/api/reconciliation-fixtures.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { ReconciliationPreview } from "./ReconciliationPreview.tsx";

// States. AW57, AW58: the preview of the acta, with props alone: nothing chosen, loading (a skeleton with the shape of the table), failed with
// a retry, and ready with the counts, the active enrollments and the difference in words.
describe("ReconciliationPreview", () => {
  it("asks for a year while none is chosen", () => {
    renderWithAdminI18n(<ReconciliationPreview view={{ kind: "idle" }} />);

    expect(screen.getByText("Elija un año lectivo para ver sus conteos.")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it("shows the loading state as a table skeleton and announces it", () => {
    renderWithAdminI18n(<ReconciliationPreview view={{ kind: "loading" }} />);

    expect(screen.getByRole("table", { name: /Registros creados y rechazados/ })).toHaveAttribute("aria-busy", "true");
    expect(screen.getByRole("table", { name: /Registros creados y rechazados/ })).toBeInTheDocument();
  });

  it("shows the error with an action to try again", async () => {
    const onRetry = vi.fn();
    renderWithAdminI18n(<ReconciliationPreview view={{ kind: "failed", message: "Sin conexión.", onRetry }} />);

    expect(screen.getByRole("alert")).toHaveTextContent("No se pudieron calcular los conteos del año lectivo");
    expect(screen.getByRole("alert")).toHaveTextContent("Sin conexión.");
    await userEvent.setup().click(screen.getByRole("button", { name: "Reintentar" }));
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it("shows the created and the rejected of the four kinds, aligned to the end, and the active enrollments", () => {
    renderWithAdminI18n(<ReconciliationPreview view={{ kind: "ready", data: preview({ enrollmentsActive: 1180 }) }} />);

    const table = screen.getByRole("table", { name: /Registros creados y rechazados/ });
    expect(within(table).getAllByRole("columnheader").map((header) => header.textContent)).toEqual([
      "Tipo",
      "Creados",
      "Rechazados en la última aplicación",
    ]);
    expect(
      within(table)
        .getAllByRole("row")
        .slice(1)
        .map((row) => within(row).getAllByRole("cell").map((cell) => cell.textContent)),
    ).toEqual([
      ["Estudiantes", "120", "2"],
      ["Encargados", "90", "0"],
      ["Vínculos", "118", "1"],
      ["Matrículas", "118", "3"],
    ]);
    expect(screen.getByText("Matrículas activas del año lectivo").nextSibling).toHaveTextContent(/^1.?180$/);
  });

  it("asks for the official total while there is none, and does not invent a difference", () => {
    renderWithAdminI18n(<ReconciliationPreview view={{ kind: "ready", data: preview() }} />);

    expect(screen.getByText("Escriba el total del listado oficial para ver la diferencia.")).toBeInTheDocument();
    expect(screen.queryByText(/El sistema tiene/)).not.toBeInTheDocument();
  });

  it.each([
    [120, "El sistema tiene 2 matrículas activas menos que el listado oficial."],
    [119, "El sistema tiene 1 matrícula activa menos que el listado oficial."],
    [117, "El sistema tiene 1 matrícula activa más que el listado oficial."],
    [100, "El sistema tiene 18 matrículas activas más que el listado oficial."],
    [118, "El sistema tiene las mismas matrículas activas que el listado oficial."],
  ])("with an official total of %i says it in words: %s", (official, sentence) => {
    renderWithAdminI18n(<ReconciliationPreview view={{ kind: "ready", data: preview({ enrollmentsActive: 118 }) }} officialCount={official} />);

    expect(screen.getByText(sentence)).toBeInTheDocument();
    expect(screen.getByText("Total del listado oficial")).toBeInTheDocument();
  });

  it("with no enrollments and an official total of zero says they are the same", () => {
    renderWithAdminI18n(<ReconciliationPreview view={{ kind: "ready", data: preview({ enrollmentsActive: 0 }) }} officialCount={0} />);

    expect(screen.getByText("El sistema tiene las mismas matrículas activas que el listado oficial.")).toBeInTheDocument();
  });
});
