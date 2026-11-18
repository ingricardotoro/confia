import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { statementSection, studentStatement } from "../../../test/contract-responses.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { StatementView, type StatementViewProps } from "./StatementView.tsx";

// States. AW57, AW58: the statement page rendered with props alone. Idle: nobody chosen, with guidance. Loading: skeletons with the
// shape of the page. Error: the error state with a retry and the trace id in the message. Blocked: a student of another institution
// or a missing permission, with no retry. Empty: a student with no account yet. Success: one section per currency. The period
// field tells a start after the end in text.
function props(override: Partial<StatementViewProps> = {}): StatementViewProps {
  return {
    state: { kind: "idle" },
    picker: {
      controlId: "statement-student",
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
    period: { from: "", to: "", onFromChange: vi.fn(), onToChange: vi.fn() },
    onPageChange: vi.fn(),
    ...override,
  };
}

describe("StatementView", () => {
  it("guides the person to choose a student before anything is shown", async () => {
    const user = userEvent.setup();
    renderWithAdminI18n(<StatementView {...props()} />);

    expect(screen.getByRole("heading", { level: 2, name: "Estado de cuenta" })).toBeInTheDocument();
    expect(screen.getByText("Elija un estudiante")).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Estudiante" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Buscar un estudiante" }));
    expect(screen.getByRole("combobox", { name: "Estudiante" })).toHaveFocus();
  });

  it("shows skeletons that announce the load while the statement is read", () => {
    renderWithAdminI18n(<StatementView {...props({ state: { kind: "loading" } })} />);

    expect(screen.getByText("Cargando el estado de cuenta.")).toBeInTheDocument();
    expect(screen.queryByText("Elija un estudiante")).not.toBeInTheDocument();
  });

  it("tells a failed read with a retry", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderWithAdminI18n(
      <StatementView {...props({ state: { kind: "error", title: "No se pudo cargar el estado de cuenta.", description: "Intente de nuevo. Identificador para soporte: abc-123", onRetry } })} />,
    );

    expect(screen.getByText(/Identificador para soporte: abc-123/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it("tells a student that is not available as an alert with no retry to press", () => {
    renderWithAdminI18n(
      <StatementView {...props({ state: { kind: "blocked", title: "No encontramos a este estudiante", description: "El estudiante no existe o no pertenece a su institución." } })} />,
    );

    expect(screen.getByText("No encontramos a este estudiante")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Reintentar" })).not.toBeInTheDocument();
  });

  it("explains a student with no movements and offers to choose another", async () => {
    const user = userEvent.setup();
    const onClear = vi.fn();
    renderWithAdminI18n(<StatementView {...props({ state: { kind: "empty" }, picker: { ...props().picker, chosenPending: true, onClear } })} />);

    expect(screen.getByText("Este estudiante todavía no tiene movimientos")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Elegir otro estudiante" }));
    expect(onClear).toHaveBeenCalledOnce();
  });

  it("shows the student chosen with the way to change the choice, and one section per currency", () => {
    const statement = studentStatement({
      sections: [
        statementSection(),
        statementSection({
          currency: "USD",
          openingBalance: { amount: "0.00", currency: "USD" },
          closingBalance: { amount: "0.00", currency: "USD" },
          lines: [],
          totalLines: 0,
        }),
      ],
    });
    renderWithAdminI18n(
      <StatementView
        {...props({
          state: { kind: "ready", statement, busy: false },
          picker: { ...props().picker, chosen: { id: statement.studentId, studentCode: "E-000001", firstName: "Ana", lastName: "Aguilar" }, chosenPending: true },
          currency: { value: "", options: [{ value: "HNL", label: "lempira hondureño (HNL)" }, { value: "USD", label: "dólar estadounidense (USD)" }], onChange: vi.fn() },
        })}
      />,
    );

    expect(screen.getByText("Aguilar, Ana (E-000001)")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Cambiar estudiante" })).toBeInTheDocument();
    expect(screen.getAllByRole("region", { name: /\((HNL|USD)\)/ })).toHaveLength(2);
    expect(screen.getByLabelText("Moneda")).toBeInTheDocument();
  });

  it("offers the way to the charges of the student once there is a statement or an empty account, and not before", async () => {
    const user = userEvent.setup();
    const onViewCharges = vi.fn();
    const { rerender } = renderWithAdminI18n(<StatementView {...props({ onViewCharges })} />);
    expect(screen.queryByRole("button", { name: "Ver cargos del estudiante" })).not.toBeInTheDocument();

    rerender(<StatementView {...props({ onViewCharges, state: { kind: "ready", statement: studentStatement(), busy: false } })} />);
    await user.click(screen.getByRole("button", { name: "Ver cargos del estudiante" }));

    expect(onViewCharges).toHaveBeenCalledOnce();
  });

  it("shows no currency selector when none is given", () => {
    renderWithAdminI18n(<StatementView {...props()} />);

    expect(screen.queryByLabelText("Moneda")).not.toBeInTheDocument();
  });

  it("tells a period that ends before it starts on the final date", async () => {
    const user = userEvent.setup();
    renderWithAdminI18n(
      <StatementView
        {...props({
          state: { kind: "invalid-period" },
          period: { from: "2027-02-01", to: "2027-01-01", toError: "La fecha final debe ser igual o posterior a la inicial.", onFromChange: vi.fn(), onToChange: vi.fn() },
        })}
      />,
    );

    expect(screen.getByLabelText("Hasta")).toBeInvalid();
    expect(screen.getByText("Corrija el período")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Corregir la fecha final" }));
    expect(screen.getByLabelText("Hasta")).toHaveFocus();
  });
});
