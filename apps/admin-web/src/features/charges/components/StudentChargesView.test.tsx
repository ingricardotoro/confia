import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { studentCharge } from "../../../test/contract-responses.ts";
import { loadedTable, onePage } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { StudentChargesView, type StudentChargesViewProps } from "./StudentChargesView.tsx";

// States. AW57, AW58: the charges of a student rendered with props alone. Idle: nobody chosen, with guidance. Loading and error: drawn by
// the table (`table.loading`, `table.error`). Blocked: a student of another institution or a missing permission, with no retry. Empty:
// `table.empty`, drawn in place of the body. Success: a charge in force with its void action and a voided one without it.
const ACTIVE = studentCharge({ id: "00000000-0000-4000-8000-0000000d0001", periodLabel: "2027-02", conceptName: "Mensualidad" });
const VOIDED = studentCharge({
  id: "00000000-0000-4000-8000-0000000d0002",
  periodLabel: "2027-01",
  conceptName: "Matrícula",
  status: "VOIDED",
  voided: { reason: "r", reversalTransactionId: "00000000-0000-4000-8000-0000000e0001", voidId: "00000000-0000-4000-8000-0000000e0002", voidedAt: "2026-11-10T15:00:00Z" },
});

function props(override: Partial<StudentChargesViewProps> = {}): StudentChargesViewProps {
  return {
    state: { kind: "ready" },
    picker: {
      controlId: "student-charges-student",
      draft: "",
      onDraftChange: vi.fn(),
      tooShort: false,
      search: "idle",
      results: [],
      onRetry: vi.fn(),
      chosenPending: false,
      onChoose: vi.fn(),
      onClear: vi.fn(),
    },
    chosen: { label: "Aguilar, Ana (E-000001)", onChange: vi.fn(), onViewStatement: vi.fn() },
    filters: {
      status: { value: "", onChange: vi.fn() },
      from: { value: "", onChange: vi.fn() },
      to: { value: "", onChange: vi.fn() },
    },
    rows: [ACTIVE, VOIDED],
    table: loadedTable,
    pagination: onePage,
    onVoid: vi.fn(),
    ...override,
  };
}

describe("StudentChargesView", () => {
  it("offers to void only the charge in force, by name, and sends the row up", async () => {
    const user = userEvent.setup();
    const onVoid = vi.fn();
    renderWithAdminI18n(<StudentChargesView {...props({ onVoid })} />);

    expect(within(screen.getByRole("row", { name: /2027-01/ })).queryByRole("button")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Anular el cargo Mensualidad, 2027-02" }));

    expect(onVoid).toHaveBeenCalledWith(ACTIVE);
  });

  it("shows what each charge has paid and what is still owed, as the server derived them, and never adds them", () => {
    const partly = studentCharge({
      id: "00000000-0000-4000-8000-0000000d0003",
      periodLabel: "2027-03",
      total: { amount: "1265.00", currency: "HNL" },
      paid: { amount: "235.00", currency: "HNL" },
      outstanding: { amount: "1030.00", currency: "HNL" },
    });
    renderWithAdminI18n(<StudentChargesView {...props({ rows: [partly] })} />);

    expect(screen.getByRole("columnheader", { name: "Pagado" })).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "Pendiente" })).toBeInTheDocument();
    const row = within(screen.getByRole("row", { name: /2027-03/ }));
    expect(row.getAllByText(/235.00/).length).toBeGreaterThan(0);
    expect(row.getAllByText(/1,030.00/).length).toBeGreaterThan(0);
  });

  it("shows the status in words and the date a charge was voided", () => {
    renderWithAdminI18n(<StudentChargesView {...props()} />);

    expect(within(screen.getByRole("row", { name: /2027-02/ })).getByText("Vigente")).toBeInTheDocument();
    const voided = within(screen.getByRole("row", { name: /2027-01/ }));
    expect(voided.getByText("Anulado")).toBeInTheDocument();
    expect(voided.getByText(/Anulado el/)).toBeInTheDocument();
  });

  it("names the student chosen and offers the statement and the change of student", async () => {
    const user = userEvent.setup();
    const chosen = { label: "Aguilar, Ana (E-000001)", onChange: vi.fn(), onViewStatement: vi.fn() };
    renderWithAdminI18n(<StudentChargesView {...props({ chosen })} />);

    expect(screen.getByText("Aguilar, Ana (E-000001)")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Ver estado de cuenta" }));
    await user.click(screen.getByRole("button", { name: "Cambiar estudiante" }));

    expect(chosen.onViewStatement).toHaveBeenCalledOnce();
    expect(chosen.onChange).toHaveBeenCalledOnce();
  });

  it("says the student is chosen when the name is not known yet", () => {
    renderWithAdminI18n(<StudentChargesView {...props({ chosen: { label: undefined, onChange: vi.fn(), onViewStatement: vi.fn() } })} />);

    expect(screen.getByText("Estudiante elegido", { selector: "p" })).toBeInTheDocument();
  });

  it("guides the person to choose a student before anything is shown", () => {
    renderWithAdminI18n(<StudentChargesView {...props({ state: { kind: "idle" }, chosen: undefined, rows: [] })} />);

    expect(screen.getByText("Elija un estudiante")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it("tells a period that ends before it starts, on the field and apart from the table", () => {
    renderWithAdminI18n(
      <StudentChargesView
        {...props({
          state: { kind: "invalid-period" },
          filters: {
            status: { value: "", onChange: vi.fn() },
            from: { value: "2027-03-01", onChange: vi.fn() },
            to: { value: "2027-01-01", error: "La fecha final debe ser igual o posterior a la inicial.", onChange: vi.fn() },
          },
        })}
      />,
    );

    expect(screen.getByText("Corrija el período")).toBeInTheDocument();
    expect(screen.getByLabelText("Período hasta")).toBeInvalid();
  });

  it("tells a student that is not available as a warning with no retry to press", () => {
    renderWithAdminI18n(<StudentChargesView {...props({ state: { kind: "blocked", title: "No encontramos a este estudiante", description: "No existe." } })} />);

    expect(screen.getByText("No encontramos a este estudiante")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it("draws the empty state, the error state and the loading state of the table", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    const { rerender } = renderWithAdminI18n(<StudentChargesView {...props({ rows: [], table: loadedTable })} />);
    expect(screen.getByText("vacío")).toBeInTheDocument();

    rerender(<StudentChargesView {...props({ rows: [], table: { ...loadedTable, error: { title: "No se pudieron cargar los cargos", description: "x", onRetry } } })} />);
    await user.click(screen.getByRole("button", { name: "Reintentar" }));
    expect(onRetry).toHaveBeenCalledOnce();

    rerender(<StudentChargesView {...props({ rows: [], table: { ...loadedTable, loading: true } })} />);
    expect(screen.getByRole("table")).toHaveAttribute("aria-busy", "true");
  });

  it("shows the result of the last void, apart from the table", () => {
    renderWithAdminI18n(<StudentChargesView {...props({ notice: { tone: "success", message: "Se anuló el cargo." } })} />);

    expect(screen.getByText("Se anuló el cargo.")).toBeInTheDocument();
  });
});
