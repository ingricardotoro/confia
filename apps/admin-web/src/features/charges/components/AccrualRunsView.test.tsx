import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { accrualRun } from "../../../test/contract-responses.ts";
import { loadedTable, onePage } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { AccrualRunsView, type AccrualRunsViewProps } from "./AccrualRunsView.tsx";

// States. AW57, AW58: the table of runs rendered with props alone. Loading: `table.loading`. Error and empty: `table.error` and
// `table.empty`, drawn by the table in place of its body. Success: a completed run and one with errors, with their counters.
const PLAN = "00000000-0000-4000-8000-000000000c01";

function props(override: Partial<AccrualRunsViewProps> = {}): AccrualRunsViewProps {
  return {
    rows: [
      accrualRun({ id: "00000000-0000-4000-8000-0000000000a1", planId: PLAN, periodStart: "2027-02-01", status: "COMPLETED", studentsEvaluated: 30, chargesCreated: 30 }),
      accrualRun({
        id: "00000000-0000-4000-8000-0000000000a2",
        planId: PLAN,
        periodStart: "2027-03-01",
        status: "COMPLETED_WITH_ERRORS",
        studentsEvaluated: 30,
        chargesCreated: 28,
        chargesExisting: 0,
        chargesFailed: 2,
      }),
      accrualRun({ id: "00000000-0000-4000-8000-0000000000a3", planId: PLAN, periodStart: "2027-04-01", status: "QUEUED", startedAt: null, finishedAt: null, studentsEvaluated: 0, chargesCreated: 0 }),
    ],
    table: loadedTable,
    pagination: onePage,
    planNames: new Map([[PLAN, "Primaria 2027"]]),
    planOptions: [{ value: PLAN, label: "Primaria 2027" }],
    filters: { plan: { value: "", onChange: vi.fn() } },
    hrefOf: (row) => `/charges/accrual/runs/${row.id}`,
    onOpen: vi.fn(),
    onCreate: vi.fn(),
    ...override,
  };
}

describe("AccrualRunsView", () => {
  it("shows the period, the plan, the state, the four counters and the dates of each run, in text", () => {
    renderWithAdminI18n(<AccrualRunsView {...props()} />);

    expect(screen.getAllByRole("columnheader").map((header) => header.textContent)).toEqual([
      "Período",
      "Plan",
      "Estado",
      "Evaluados",
      "Creados",
      "Ya existían",
      "Con error",
      "Solicitada",
      "Finalizada",
    ]);
    const first = within(screen.getByRole("row", { name: /2027-02/ }));
    expect(first.getByText("Primaria 2027")).toBeInTheDocument();
    expect(first.getByText("Completada")).toBeInTheDocument();
    expect(first.getAllByText("30")).toHaveLength(2);
    const second = within(screen.getByRole("row", { name: /2027-03/ }));
    expect(second.getByText("Completada con errores")).toBeInTheDocument();
    expect(second.getByText("28")).toBeInTheDocument();
    const queued = within(screen.getByRole("row", { name: /2027-04/ }));
    expect(queued.getByText("En cola")).toBeInTheDocument();
    expect(queued.getByText("Todavía no")).toBeInTheDocument();
  });

  it("links each period to its run, and a plain click is a navigation of the router", async () => {
    const user = userEvent.setup();
    const onOpen = vi.fn();
    renderWithAdminI18n(<AccrualRunsView {...props({ onOpen })} />);

    const link = screen.getByRole("link", { name: "2027-02" });
    expect(link).toHaveAttribute("href", "/charges/accrual/runs/00000000-0000-4000-8000-0000000000a1");
    await user.click(link);

    expect(onOpen).toHaveBeenCalledWith(expect.objectContaining({ periodStart: "2027-02-01" }));
  });

  it("says a plan is not available when its name did not load", () => {
    renderWithAdminI18n(<AccrualRunsView {...props({ planNames: new Map() })} />);

    expect(within(screen.getByRole("row", { name: /2027-02/ })).getByText("No disponible")).toBeInTheDocument();
  });

  it("filters by plan, with the plans as options", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithAdminI18n(<AccrualRunsView {...props({ filters: { plan: { value: "", onChange } } })} />);

    await user.selectOptions(screen.getByRole("combobox", { name: "Plan de cobro" }), "Primaria 2027");

    expect(onChange).toHaveBeenCalledWith(PLAN);
  });

  it("goes to the generation with the primary action", async () => {
    const user = userEvent.setup();
    const onCreate = vi.fn();
    renderWithAdminI18n(<AccrualRunsView {...props({ onCreate })} />);

    await user.click(screen.getByRole("button", { name: "Generar cargos" }));

    expect(onCreate).toHaveBeenCalledOnce();
  });

  it("shows the empty state, the loading skeleton and the error with a retry in place of the rows", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    const { rerender } = renderWithAdminI18n(
      <AccrualRunsView {...props({ rows: [], table: { empty: { title: "Todavía no hay ejecuciones", description: "Cuando genere", action: { label: "Generar cargos", onAction: vi.fn() } } } })} />,
    );
    expect(screen.getByText("Todavía no hay ejecuciones")).toBeInTheDocument();

    rerender(<AccrualRunsView {...props({ rows: [], table: { ...loadedTable, loading: true } })} />);
    expect(screen.getByRole("table", { name: "Ejecuciones de generación de cargos" })).toHaveAttribute("aria-busy", "true");

    rerender(<AccrualRunsView {...props({ rows: [], table: { ...loadedTable, error: { title: "No se pudieron cargar las ejecuciones", description: "Sin conexión.", onRetry } } })} />);
    await user.click(screen.getByRole("button", { name: "Reintentar" }));
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it("warns when the plans did not load, with a retry", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderWithAdminI18n(<AccrualRunsView {...props({ lookupFailure: { onRetry } })} />);

    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(onRetry).toHaveBeenCalledOnce();
  });
});
