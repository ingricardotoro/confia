import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { chargeSimulation } from "../../../test/contract-responses.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { SimulatorView, type SimulatorViewProps } from "./SimulatorView.tsx";

// States. AW57, AW58: the simulator of charges rendered with props alone. Loading: the plans, the periods and the result each have their skeleton or status.
// Error: the plans, the periods and the simulation each have their error with a retry. Empty: no plan, no period, a plan with no concept for the period.
// Idle: nothing asked yet, and not enrolled, an answer of its own. Success: the table of the simulation, every figure exactly as it arrived.
const picker = {
  controlId: "simulator-student",
  draft: "",
  onDraftChange: vi.fn(),
  tooShort: false,
  search: "idle",
  results: [],
  onRetry: vi.fn(),
  chosenPending: false,
  onChoose: vi.fn(),
  onClear: vi.fn(),
} as const;

function props(override: Partial<SimulatorViewProps> = {}): SimulatorViewProps {
  return {
    plans: { state: { kind: "ready" }, value: "p1", options: [{ value: "p1", label: "Primaria 2027" }], onChange: vi.fn() },
    periods: { state: { kind: "ready" }, value: "2027-02-01", options: [{ value: "2027-02-01", label: "2027-02, vence el 5 feb 2027" }], onChange: vi.fn() },
    student: { picker },
    canSimulate: true,
    simulating: false,
    onSimulate: vi.fn(),
    result: { kind: "idle" },
    onGoToPlans: vi.fn(),
    ...override,
  };
}

const ready = (simulation = chargeSimulation()): SimulatorViewProps["result"] => ({
  kind: "ready",
  simulation,
  planName: "Primaria 2027",
  periodLabel: "2027-02",
  conceptName: (id) => (id === "00000000-0000-4000-8000-000000007001" ? "MENS · Mensualidad" : "No disponible"),
});

describe("SimulatorView", () => {
  it("AW57: shows the figures of the acceptance example exactly as they arrived, and says that nothing was written", () => {
    renderWithAdminI18n(<SimulatorView {...props({ result: ready() })} />);

    const row = within(screen.getByRole("row", { name: /MENS · Mensualidad/ }));
    expect(row.getByText("L 1,100.00")).toBeInTheDocument();
    expect(row.getByText("BECA25")).toBeInTheDocument();
    expect(row.getByText("L 275.00")).toBeInTheDocument();
    expect(row.getByText("L 825.00")).toBeInTheDocument();
    expect(row.getByText("L 123.75")).toBeInTheDocument();
    expect(row.getByText("L 948.75")).toBeInTheDocument();
    expect(screen.getByText(/no se generó ningún cargo ni se escribió nada/)).toBeInTheDocument();
  });

  it("AW57: reports the simulation when the form is sent and draws no way to send it while the choice is incomplete", async () => {
    const user = userEvent.setup();
    const onSimulate = vi.fn();
    const { rerender } = renderWithAdminI18n(<SimulatorView {...props({ onSimulate })} />);
    await user.click(screen.getByRole("button", { name: "Simular" }));
    expect(onSimulate).toHaveBeenCalledTimes(1);

    rerender(<SimulatorView {...props({ onSimulate, canSimulate: false })} />);
    expect(screen.getByRole("button", { name: "Simular" })).toBeDisabled();
  });

  it("AW57: reports the plan and the period the person chooses, and keeps the period closed until its periods are ready", async () => {
    const user = userEvent.setup();
    const onPlan = vi.fn();
    const onPeriod = vi.fn();
    const { rerender } = renderWithAdminI18n(
      <SimulatorView
        {...props({
          plans: { state: { kind: "ready" }, value: "", options: [{ value: "p1", label: "Primaria 2027" }], onChange: onPlan },
          periods: { state: { kind: "ready" }, value: "", options: [{ value: "2027-02-01", label: "2027-02" }], onChange: onPeriod },
        })}
      />,
    );
    await user.selectOptions(screen.getByLabelText(/^Plan de cobro/), "Primaria 2027");
    await user.selectOptions(screen.getByLabelText(/^Período/), "2027-02");
    expect(onPlan).toHaveBeenCalledWith("p1");
    expect(onPeriod).toHaveBeenCalledWith("2027-02-01");

    rerender(<SimulatorView {...props({ periods: { state: { kind: "idle" }, value: "", options: [], onChange: onPeriod } })} />);
    expect(screen.getByLabelText(/^Período/)).toBeDisabled();
  });

  it("AW57: offers the search of a student while none is chosen, and the chosen one with how to change it afterwards", async () => {
    const user = userEvent.setup();
    const onClear = vi.fn();
    const { rerender } = renderWithAdminI18n(<SimulatorView {...props()} />);
    expect(screen.getByRole("combobox", { name: /^Estudiante/ })).toBeInTheDocument();

    rerender(<SimulatorView {...props({ student: { picker, chosen: { label: "Pérez, Ana (EST-0001)", onClear } } })} />);
    await user.click(screen.getByRole("button", { name: "Cambiar estudiante" }));
    expect(screen.getByText("Pérez, Ana (EST-0001)")).toBeInTheDocument();
    expect(onClear).toHaveBeenCalled();
  });

  it("AW57: shows a dash where a line has no amount, no scholarship as such, and the code of a line that would fail", () => {
    renderWithAdminI18n(
      <SimulatorView
        {...props({
          result: ready(
            chargeSimulation({
              lines: [
                { paymentConceptId: "00000000-0000-4000-8000-000000007001", outcome: "CREATED", base: { amount: "1000.00", currency: "HNL" }, tax: { amount: "150.00", currency: "HNL" }, total: { amount: "1150.00", currency: "HNL" } },
                { paymentConceptId: "00000000-0000-4000-8000-000000007002", outcome: "ERROR", errorCode: "NO_PRICE" },
              ],
            }),
          ),
        })}
      />,
    );

    expect(within(screen.getByRole("row", { name: /MENS · Mensualidad/ })).getByText("Sin beca")).toBeInTheDocument();
    const failing = within(screen.getByRole("row", { name: /Con error: NO_PRICE/ }));
    expect(failing.getAllByText("—").length).toBe(5);
  });

  it("AW57: tells idle, loading, not enrolled and error each in words, the error with its retry", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    const { rerender } = renderWithAdminI18n(<SimulatorView {...props()} />);
    expect(screen.getByText("Todavía no se ha simulado nada")).toBeInTheDocument();

    rerender(<SimulatorView {...props({ result: { kind: "loading" } })} />);
    expect(screen.getByText("Calculando la simulación.")).toBeInTheDocument();

    rerender(<SimulatorView {...props({ result: { kind: "not-enrolled" } })} />);
    expect(screen.getByText("El estudiante no está matriculado en ese plan")).toBeInTheDocument();

    rerender(<SimulatorView {...props({ result: { kind: "error", description: "Ocurrió un error inesperado.", onRetry } })} />);
    await user.click(screen.getByRole("button", { name: "Reintentar" }));
    expect(onRetry).toHaveBeenCalled();
  });

  it("AW57: tells a plan that generates no charge for the period, with the way to choose another", () => {
    renderWithAdminI18n(<SimulatorView {...props({ result: ready(chargeSimulation({ lines: [] })) })} />);

    expect(screen.getByText("El plan no genera cargos para este período")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Elegir otro período" })).toBeInTheDocument();
  });

  it("AW57: tells the loading, the error and the absence of plans, and of periods, each with its way forward", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    const onGoToPlans = vi.fn();
    const { rerender } = renderWithAdminI18n(<SimulatorView {...props({ plans: { state: { kind: "loading" }, value: "", options: [], onChange: vi.fn() } })} />);
    expect(screen.getByRole("heading", { level: 2, name: "Simulador de cargos" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Simular" })).not.toBeInTheDocument();

    rerender(<SimulatorView {...props({ plans: { state: { kind: "error", description: "Sin conexión.", onRetry }, value: "", options: [], onChange: vi.fn() } })} />);
    await user.click(screen.getByRole("button", { name: "Reintentar" }));
    expect(onRetry).toHaveBeenCalled();

    rerender(<SimulatorView {...props({ onGoToPlans, plans: { state: { kind: "empty" }, value: "", options: [], onChange: vi.fn() } })} />);
    await user.click(screen.getByRole("button", { name: "Ir a los planes de cobro" }));
    expect(onGoToPlans).toHaveBeenCalled();

    rerender(<SimulatorView {...props({ periods: { state: { kind: "empty" }, value: "", options: [], onChange: vi.fn() } })} />);
    expect(screen.getByText("El plan no tiene períodos")).toBeInTheDocument();
    rerender(<SimulatorView {...props({ periods: { state: { kind: "error", description: "Sin conexión.", onRetry }, value: "", options: [], onChange: vi.fn() } })} />);
    expect(screen.getByText("No se pudieron cargar los períodos del plan.")).toBeInTheDocument();
  });

  it("AW57: warns that the names of the concepts could not be loaded, with a retry", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderWithAdminI18n(<SimulatorView {...props({ lookupFailure: { onRetry } })} />);

    await user.click(screen.getByRole("button", { name: "Reintentar" }));
    expect(onRetry).toHaveBeenCalled();
  });
});
