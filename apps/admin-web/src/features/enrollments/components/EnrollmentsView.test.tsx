import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { studentEnrollment } from "../../../test/contract-responses.ts";
import { loadedTable, onePage } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { EnrollmentsView, type EnrollmentsViewProps } from "./EnrollmentsView.tsx";

// States. AW57, AW58: the table of enrollments rendered with props alone. Loading: `table.loading`. Error and empty:
// `table.error` and `table.empty`, drawn by the table. Success: the rows, with the actions of an active enrollment and only
// the detail for a final one. The filters are a child with its own test.
const select = { value: "", options: [], onChange: vi.fn() };

function props(override: Partial<EnrollmentsViewProps> = {}): EnrollmentsViewProps {
  return {
    rows: [studentEnrollment({ studentFirstName: "Ana", studentLastName: "Pérez" })],
    table: loadedTable,
    pagination: onePage,
    filters: { year: { ...select, withAll: false }, grade: select, section: select, status: { value: "", onChange: vi.fn() } },
    onCreate: vi.fn(),
    onDetail: vi.fn(),
    onTransfer: vi.fn(),
    onExit: vi.fn(),
    ...override,
  };
}

describe("EnrollmentsView", () => {
  it("AW57: names each button with the student it acts on and reports the row it was pressed on", async () => {
    const user = userEvent.setup();
    const onDetail = vi.fn();
    const onTransfer = vi.fn();
    const onExit = vi.fn();
    const row = studentEnrollment({ studentFirstName: "Ana", studentLastName: "Pérez" });
    renderWithAdminI18n(<EnrollmentsView {...props({ rows: [row], onDetail, onTransfer, onExit })} />);

    await user.click(screen.getByRole("button", { name: "Ver detalle de la matrícula de Ana Pérez" }));
    await user.click(screen.getByRole("button", { name: "Trasladar de sección a Ana Pérez" }));
    await user.click(screen.getByRole("button", { name: "Registrar salida de Ana Pérez" }));

    expect(onDetail).toHaveBeenCalledWith(row);
    expect(onTransfer).toHaveBeenCalledWith(row);
    expect(onExit).toHaveBeenCalledWith(row);
  });

  it("AW57: offers only the detail on a final enrollment, and shows the day of exit", () => {
    renderWithAdminI18n(
      <EnrollmentsView {...props({ rows: [studentEnrollment({ studentLastName: "Mejía", status: "GRADUATED", withdrawnOn: "2026-11-01" })] })} />,
    );

    const row = screen.getByRole("row", { name: /Mejía/ });
    expect(within(row).getByText("Graduada")).toBeInTheDocument();
    expect(within(row).getAllByRole("button")).toHaveLength(1);
    expect(within(row).queryByText("Sin fecha de salida")).not.toBeInTheDocument();
  });

  it("AW58: the loading state is a busy table, the error a retry and the empty a way forward", () => {
    const onRetry = vi.fn();
    const { rerender } = renderWithAdminI18n(<EnrollmentsView {...props({ rows: [], table: { ...loadedTable, loading: true } })} />);
    expect(screen.getByRole("table")).toHaveAttribute("aria-busy", "true");

    rerender(<EnrollmentsView {...props({ rows: [], table: { ...loadedTable, error: { title: "No se pudo", description: "Detalle", onRetry } } })} />);
    expect(screen.getByRole("button", { name: "Reintentar" })).toBeInTheDocument();

    rerender(<EnrollmentsView {...props({ rows: [] })} />);
    expect(screen.getByText("vacío")).toBeInTheDocument();
  });

  it("AW58: shows the notice of the last action and the warning of the lookups with a retry", () => {
    renderWithAdminI18n(
      <EnrollmentsView {...props({ notice: { tone: "success", message: "Se matriculó a «Ana Pérez»." }, lookupFailure: { onRetry: vi.fn() } })} />,
    );

    expect(screen.getByText("Se matriculó a «Ana Pérez».")).toBeInTheDocument();
    expect(screen.getByText(/No se pudieron cargar las opciones/)).toBeInTheDocument();
  });
});
