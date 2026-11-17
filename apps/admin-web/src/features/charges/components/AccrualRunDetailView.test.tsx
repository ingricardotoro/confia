import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { AccrualRunItem } from "../../../shared/api/charges-operations.ts";
import { accrualRun, accrualRunItem } from "../../../test/contract-responses.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import type { AccrualNames } from "./AccrualView.tsx";
import { AccrualRunDetailView, type AccrualRunDetailState, type AccrualRunDetailViewProps } from "./AccrualRunDetailView.tsx";

// States. AW57, AW58: the detail of a run rendered with props alone. Loading: skeletons with the shape of the page. Error: the
// error state with a retry. Empty: a run with no rows yet (still going) or none at all (rejected). Success: the run with its counters
// and its rows, in each of the six states a run goes through, told in a live region.
const NAMES: AccrualNames = {
  student: (id) => (id.endsWith("5001") ? "Aguilar, Ana (E-000001)" : "Banegas, Sofía (E-000003)"),
  concept: () => "COL, Colegiatura",
};

function ready(
  run: Parameters<typeof accrualRun>[0] = {},
  items: readonly AccrualRunItem[] = [accrualRunItem()],
  refreshFailed = false,
): AccrualRunDetailState {
  return {
    kind: "ready",
    run: accrualRun(run),
    planName: "Primaria 2027",
    items,
    names: NAMES,
    pagination: { page: 0, size: 25, total: items.length, busy: false, onPageChange: vi.fn() },
    refreshFailed,
  };
}

function props(override: Partial<AccrualRunDetailViewProps> = {}): AccrualRunDetailViewProps {
  return {
    state: ready(),
    backHref: "/charges/accrual/runs",
    onBack: vi.fn(),
    generationHref: "/charges/accrual",
    onOpenGeneration: vi.fn(),
    planHref: "/charges/plans/p1",
    onOpenPlan: vi.fn(),
    onRecalculate: vi.fn(),
    ...override,
  };
}

describe("AccrualRunDetailView", () => {
  it("shows the run with its state, its data and its counters", () => {
    renderWithAdminI18n(<AccrualRunDetailView {...props({ state: ready({ studentsEvaluated: 30, chargesCreated: 28, chargesFailed: 2, status: "COMPLETED_WITH_ERRORS" }) })} />);

    expect(screen.getByRole("heading", { level: 2, name: "Ejecución de Primaria 2027, período 2027-02" })).toBeInTheDocument();
    const summary = within(screen.getByLabelText("Datos de la ejecución"));
    expect(summary.getByText("Primaria 2027")).toBeInTheDocument();
    expect(summary.getByText("Confirmada con una vista previa")).toBeInTheDocument();
    expect(screen.getAllByText("Completada con errores").length).toBeGreaterThan(0);
    expect(screen.getByText("30")).toBeInTheDocument();
    expect(screen.getByText("28")).toBeInTheDocument();
  });

  it.each([
    ["QUEUED", /La ejecución está en cola\. Esta página se actualiza sola\./],
    ["RUNNING", /La ejecución está en curso: 12 estudiantes evaluados hasta ahora\./],
    ["COMPLETED", /La ejecución terminó: se crearon 2 cargos\./],
    ["COMPLETED_WITH_ERRORS", /La ejecución terminó con errores: se crearon 2 cargos y 1 renglón quedó con error\./],
    ["REJECTED", /La ejecución se rechazó y no se escribió nada en el libro mayor\./],
    ["FAILED", /La ejecución falló y no se completó\./],
  ] as const)("tells a %s run in the live region", (status, text) => {
    renderWithAdminI18n(
      <AccrualRunDetailView {...props({ state: ready({ status, studentsEvaluated: 12, chargesCreated: 2, chargesFailed: 1, finishedAt: null }) })} />,
    );

    expect(screen.getByText(text).closest("[aria-live='polite']")).not.toBeNull();
  });

  it("explains a rejection because the digest changed, and offers to calculate again", async () => {
    const user = userEvent.setup();
    const onRecalculate = vi.fn();
    renderWithAdminI18n(<AccrualRunDetailView {...props({ onRecalculate, state: ready({ status: "REJECTED", failureCode: "preview-outdated", chargesCreated: 0 }, []) })} />);

    expect(screen.getByText(/Los datos cambiaron desde la vista previa/)).toBeInTheDocument();
    expect(screen.getByText(/Calcule la vista previa de nuevo y confirme otra vez/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Calcular de nuevo" }));
    expect(onRecalculate).toHaveBeenCalledOnce();
  });

  it("explains why a failed run did not run, in readable text and never the raw code", () => {
    renderWithAdminI18n(<AccrualRunDetailView {...props({ state: ready({ status: "FAILED", failureCode: "retries-exhausted" }, []) })} />);

    expect(screen.getByText(/El sistema agotó sus reintentos/)).toBeInTheDocument();
    expect(screen.queryByText("retries-exhausted")).not.toBeInTheDocument();
  });

  it("shows each item with its student, its concept and its result, and explains the error of the ones that failed", () => {
    const items = [
      accrualRunItem({ studentId: "00000000-0000-4000-8000-000000005001" }),
      accrualRunItem({ studentId: "00000000-0000-4000-8000-000000005003", outcome: "ERROR", errorCode: "AMBIGUOUS_PRICE", chargeId: null }),
    ];
    renderWithAdminI18n(<AccrualRunDetailView {...props({ state: ready({ status: "COMPLETED_WITH_ERRORS", chargesFailed: 1 }, items) })} />);

    const table = within(screen.getByRole("table", { name: "Renglones de la ejecución: un estudiante y un concepto por renglón" }));
    const created = within(table.getByRole("row", { name: /Aguilar, Ana/ }));
    expect(created.getByText("Creado")).toBeInTheDocument();
    expect(created.getByText("Sin observaciones")).toBeInTheDocument();
    const failed = within(table.getByRole("row", { name: /Banegas, Sofía/ }));
    expect(failed.getByText("Precio ambiguo")).toBeInTheDocument();
    expect(failed.getByText(/Dos listas de precios del mismo nivel/)).toBeInTheDocument();
  });

  it("asks the server for another page of items", async () => {
    const user = userEvent.setup();
    const onPageChange = vi.fn();
    const state = ready({}, [accrualRunItem()]);
    renderWithAdminI18n(<AccrualRunDetailView {...props({ state: { ...state, pagination: { page: 0, size: 1, total: 3, busy: false, onPageChange } } as AccrualRunDetailState })} />);

    await user.click(within(screen.getByRole("navigation", { name: "Páginas de los renglones de la ejecución" })).getByRole("button", { name: /Siguiente/ }));

    expect(onPageChange).toHaveBeenCalledWith(1);
  });

  it("tells there are no rows yet while the run is going, and none for a run that did not evaluate anyone", () => {
    const { rerender } = renderWithAdminI18n(<AccrualRunDetailView {...props({ state: ready({ status: "RUNNING", finishedAt: null }, []) })} />);
    expect(screen.getByText("Todavía no hay renglones")).toBeInTheDocument();

    rerender(<AccrualRunDetailView {...props({ state: ready({ status: "REJECTED", failureCode: "plan-not-active" }, []) })} />);
    expect(screen.getByText("Esta ejecución no tiene renglones")).toBeInTheDocument();
  });

  it("says the last re-read failed and keeps the last answer on screen", () => {
    renderWithAdminI18n(<AccrualRunDetailView {...props({ state: ready({ status: "RUNNING", finishedAt: null }, [], true) })} />);

    expect(screen.getByText(/No se pudo actualizar el avance/)).toBeInTheDocument();
    expect(screen.getByText("En curso")).toBeInTheDocument();
  });

  it("shows skeletons while the run loads, with the way back", () => {
    renderWithAdminI18n(<AccrualRunDetailView {...props({ state: { kind: "loading" } })} />);

    expect(screen.getByRole("link", { name: "Volver a las ejecuciones" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2, name: "Ejecuciones de generación" }).closest("section")).toHaveAttribute("aria-busy", "true");
  });

  it("shows the error with a retry", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderWithAdminI18n(<AccrualRunDetailView {...props({ state: { kind: "error", title: "No se pudo cargar la ejecución", description: "El registro ya no existe.", onRetry } })} />);

    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(screen.getByText("No se pudo cargar la ejecución")).toBeInTheDocument();
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it("links to the plan, the runs and the generation", async () => {
    const user = userEvent.setup();
    const p = props();
    renderWithAdminI18n(<AccrualRunDetailView {...p} />);

    await user.click(screen.getByRole("link", { name: "Primaria 2027" }));
    await user.click(screen.getByRole("link", { name: "Ejecuciones de generación" }));
    await user.click(screen.getByRole("link", { name: "Generación de cargos" }));

    expect(p.onOpenPlan).toHaveBeenCalledOnce();
    expect(p.onBack).toHaveBeenCalledOnce();
    expect(p.onOpenGeneration).toHaveBeenCalledOnce();
  });

  it("warns when the names did not load, with a retry", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderWithAdminI18n(<AccrualRunDetailView {...props({ lookupFailure: { onRetry } })} />);

    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(onRetry).toHaveBeenCalledOnce();
  });
});
