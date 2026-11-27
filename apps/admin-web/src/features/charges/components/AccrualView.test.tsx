import { fireEvent, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { accrualPreview, accrualPreviewItem } from "../../../test/contract-responses.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { AccrualView, type AccrualNames, type AccrualResultState, type AccrualViewProps } from "./AccrualView.tsx";

// States. AW57, AW58: the generation page rendered with props alone. Loading: the plans still loading (skeletons with the shape of
// the form) and the preview being calculated (a status region and skeletons). Empty: no active plan, no period in the plan, and a
// preview with no rows or whose filter leaves none. Error: the plans, the periods and the preview fail, each with a Retry. Success:
// the summary, the totals as the server sent them and the rows. Idle, before a preview is asked for, is a state of its own.
const STUDENT_A = "00000000-0000-4000-8000-000000005001";
const STUDENT_B = "00000000-0000-4000-8000-000000005002";
const STUDENT_C = "00000000-0000-4000-8000-000000005003";
const NAMES: AccrualNames = {
  student: (id) => ({ [STUDENT_A]: "Aguilar, Ana (E-000001)", [STUDENT_B]: "Aguilar, Luis (E-000002)", [STUDENT_C]: "Banegas, Sofía (E-000003)" })[id] ?? "No disponible",
  concept: () => "COL, Colegiatura",
};

function ready(override: Partial<Extract<AccrualResultState, { kind: "ready" }>["preview"]> = {}): AccrualResultState {
  return {
    kind: "ready",
    preview: accrualPreview({
      items: [
        accrualPreviewItem({ studentId: STUDENT_A }),
        accrualPreviewItem({ studentId: STUDENT_B }),
        { studentId: STUDENT_C, paymentConceptId: "00000000-0000-4000-8000-000000007001", outcome: "ERROR", errorCode: "NO_PRICE", benefits: [] },
      ],
      ...override,
    }),
    planName: "Primaria 2027",
    periodLabel: "2027-02",
    planHref: "/charges/plans/p1",
    names: NAMES,
  };
}

function props(override: Partial<AccrualViewProps> = {}): AccrualViewProps {
  return {
    plans: { state: { kind: "ready" }, value: "p1", options: [{ value: "p1", label: "Primaria 2027" }], onChange: vi.fn() },
    periods: { state: { kind: "ready" }, value: "2027-02-01", options: [{ value: "2027-02-01", label: "2027-02, vence el 5 feb 2027" }], onChange: vi.fn() },
    canPreview: true,
    previewing: false,
    onPreview: vi.fn(),
    result: ready(),
    onConfirm: vi.fn(),
    onViewRuns: vi.fn(),
    onOpenPlan: vi.fn(),
    onOpenPostingAccounts: vi.fn(),
    onGoToPlans: vi.fn(),
    ...override,
  };
}

describe("AccrualView", () => {
  it("shows the scholarship of a row with its code, the benefit and the net base, and the totals with the scholarships and the net base, as the server sent them", () => {
    const money = (amount: string) => ({ amount, currency: "HNL" });
    renderWithAdminI18n(
      <AccrualView
        {...props({
          result: ready({
            items: [
              accrualPreviewItem({
                studentId: STUDENT_A,
                base: money("1100.00"),
                scholarshipAwardId: "00000000-0000-4000-8000-0000000f0001",
                scholarshipCode: "BECA25",
                benefitAmount: money("275.00"),
                benefits: [{ source: "SCHOLARSHIP", code: "BECA25", scholarshipAwardId: "00000000-0000-4000-8000-0000000f0001", amount: money("275.00") }],
                netBase: money("825.00"),
                tax: money("123.75"),
                total: money("948.75"),
              }),
              accrualPreviewItem({ studentId: STUDENT_B }),
            ],
            totals: [{ currency: "HNL", charges: 2, base: money("2600.00"), benefitAmount: money("275.00"), netBase: money("2325.00"), tax: money("348.75"), total: money("2673.75") }],
          }),
        })}
      />,
    );

    const totals = within(screen.getByRole("table", { name: "Importes de los cargos por crear, por moneda" }));
    expect(totals.getByText("275.00 lempiras hondureños")).toBeInTheDocument();
    expect(totals.getByText("2,325.00 lempiras hondureños")).toBeInTheDocument();
    const rows = screen.getByRole("table", { name: "Renglones de la vista previa: un estudiante y un concepto por renglón" });
    const withScholarship = within(within(rows).getByRole("row", { name: /Aguilar, Ana/ }));
    expect(withScholarship.getByText("Beca BECA25")).toBeInTheDocument();
    expect(withScholarship.getByText("L 1,100.00")).toBeInTheDocument();
    // The line of the scholarship and the benefit of the row are the same figure, each as the server sent it.
    expect(withScholarship.getAllByText("L 275.00")).toHaveLength(2);
    expect(withScholarship.getByText("L 825.00")).toBeInTheDocument();
    expect(within(within(rows).getByRole("row", { name: /Aguilar, Luis/ })).getByText("Sin beneficios")).toBeInTheDocument();
  });

  it("lists every benefit of a row in the order the server applied it, the scholarship and then the discount, with the figures as they arrived", () => {
    const money = (amount: string) => ({ amount, currency: "HNL" });
    renderWithAdminI18n(
      <AccrualView
        {...props({
          result: ready({
            items: [
              accrualPreviewItem({
                studentId: STUDENT_A,
                base: money("1000.00"),
                benefitAmount: money("280.00"),
                benefits: [
                  { source: "SCHOLARSHIP", code: "BECA20", scholarshipAwardId: "00000000-0000-4000-8000-0000000f0001", amount: money("200.00") },
                  { source: "DISCOUNT", code: "CAMP10", discountId: "00000000-0000-4000-8000-0000000e0001", amount: money("80.00") },
                ],
                netBase: money("720.00"),
                tax: money("108.00"),
                total: money("828.00"),
              }),
            ],
          }),
        })}
      />,
    );

    const rows = screen.getByRole("table", { name: "Renglones de la vista previa: un estudiante y un concepto por renglón" });
    const row = within(within(rows).getByRole("row", { name: /Aguilar, Ana/ }));
    const lines = row.getAllByRole("listitem");
    expect(lines.map((line) => line.textContent)).toEqual([expect.stringMatching(/^Beca BECA20.*200\.00/), expect.stringMatching(/^Descuento CAMP10.*80\.00/)]);
    expect(row.getByText("L 280.00")).toBeInTheDocument();
    expect(row.getByText("L 720.00")).toBeInTheDocument();
    expect(row.getByText("L 108.00")).toBeInTheDocument();
    expect(row.getByText("L 828.00")).toBeInTheDocument();
  });

  it("shows the summary of the preview with the figures and the totals exactly as the server sent them", () => {
    renderWithAdminI18n(<AccrualView {...props()} />);

    expect(screen.getByRole("heading", { level: 2, name: "Generación de cargos" })).toBeInTheDocument();
    const summary = within(screen.getByRole("region", { name: "Resumen de la vista previa" }));
    expect(summary.getByText("Estudiantes evaluados")).toBeInTheDocument();
    expect(summary.getByText("Cargos por crear")).toBeInTheDocument();
    const totals = within(screen.getByRole("table", { name: "Importes de los cargos por crear, por moneda" }));
    // With no scholarship the net base is the base, so the figure is drawn in both columns.
    expect(totals.getAllByText("3,000.00 lempiras hondureños")).toHaveLength(2);
    expect(totals.getByText("450.00 lempiras hondureños")).toBeInTheDocument();
    expect(totals.getByText("3,450.00 lempiras hondureños")).toBeInTheDocument();
    expect(summary.getByText("Sin precio vigente: 1 renglón.")).toBeInTheDocument();
  });

  it("lists a row for each student and concept with its amounts, and tells an error in readable text with no amounts", () => {
    renderWithAdminI18n(<AccrualView {...props()} />);

    const table = screen.getByRole("table", { name: "Renglones de la vista previa: un estudiante y un concepto por renglón" });
    const created = within(within(table).getByRole("row", { name: /Aguilar, Ana/ }));
    expect(created.getByText("Se creará")).toBeInTheDocument();
    expect(created.getByText("1,725.00 lempiras hondureños")).toBeInTheDocument();
    const failed = within(within(table).getByRole("row", { name: /Banegas, Sofía/ }));
    expect(failed.getByText("Sin precio vigente")).toBeInTheDocument();
    // The base, the benefit, the net base, the tax and the total: each has no amount.
    expect(failed.getAllByText("Sin importe")).toHaveLength(5);
  });

  it("filters the rows the server sent by error and by error code, and offers the way back when none match", async () => {
    const user = userEvent.setup();
    renderWithAdminI18n(<AccrualView {...props()} />);

    await user.selectOptions(screen.getByRole("combobox", { name: "Mostrar" }), "Solo con error");
    expect(screen.queryByRole("row", { name: /Aguilar, Ana/ })).not.toBeInTheDocument();
    expect(screen.getByRole("row", { name: /Banegas, Sofía/ })).toBeInTheDocument();
    await user.selectOptions(screen.getByRole("combobox", { name: "Mostrar" }), "Todos los renglones");
    expect(screen.getByRole("row", { name: /Aguilar, Ana/ })).toBeInTheDocument();
  });

  it("confirms with the success button when the preview has charges to create and nothing blocks it", async () => {
    const user = userEvent.setup();
    const onConfirm = vi.fn();
    renderWithAdminI18n(<AccrualView {...props({ onConfirm })} />);

    await user.click(screen.getByRole("button", { name: "Confirmar generación" }));

    expect(onConfirm).toHaveBeenCalledOnce();
  });

  it("explains each blocker in plain words, links to the plan when it is the plan's, and does not allow the confirmation", async () => {
    const user = userEvent.setup();
    const onOpenPlan = vi.fn();
    renderWithAdminI18n(<AccrualView {...props({ onOpenPlan, result: ready({ blockers: ["plan-not-active", "posting-accounts-incomplete", "plan-target-outside-year"] }) })} />);

    expect(screen.getByText("La generación no se puede confirmar todavía")).toBeInTheDocument();
    expect(screen.getByText("El plan no está activo. Solo un plan activo genera cargos.")).toBeInTheDocument();
    expect(screen.getByText(/Falta configurar el mapa de cuentas contables/)).toBeInTheDocument();
    expect(screen.getByText(/queda fuera del año lectivo del plan/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Confirmar generación" })).toBeDisabled();
    await user.click(screen.getByRole("link", { name: "Abrir el plan" }));
    expect(onOpenPlan).toHaveBeenCalledOnce();
  });

  it("does not link to the plan when the only blocker is the posting accounts, which the plan cannot fix", () => {
    renderWithAdminI18n(<AccrualView {...props({ result: ready({ blockers: ["posting-accounts-incomplete"] }) })} />);

    expect(screen.queryByRole("link", { name: "Abrir el plan" })).not.toBeInTheDocument();
  });

  it("links the blocker of the posting accounts to the map of the chart of accounts, by its address, and leaves a modified click to the browser", async () => {
    const user = userEvent.setup();
    const onOpenPostingAccounts = vi.fn();
    renderWithAdminI18n(<AccrualView {...props({ onOpenPostingAccounts, result: ready({ blockers: ["posting-accounts-incomplete"] }) })} />);
    const link = screen.getByRole("link", { name: "Abrir el mapa de cuentas de cargos" });

    expect(link).toHaveAttribute("href", "/ledger/posting-accounts");
    fireEvent.click(link, { ctrlKey: true });
    expect(onOpenPostingAccounts).not.toHaveBeenCalled();
    await user.click(link);
    expect(onOpenPostingAccounts).toHaveBeenCalledOnce();
  });

  it("does not link to the map when the posting accounts are not a blocker", () => {
    renderWithAdminI18n(<AccrualView {...props({ result: ready({ blockers: ["plan-not-active"] }) })} />);

    expect(screen.queryByRole("link", { name: "Abrir el mapa de cuentas de cargos" })).not.toBeInTheDocument();
  });

  it("says there is nothing to confirm when no charge would be created, and disables the confirmation", () => {
    renderWithAdminI18n(<AccrualView {...props({ result: ready({ toCreate: 0, totals: [], items: [accrualPreviewItem({ outcome: "ALREADY_ACCRUED" })] }) })} />);

    expect(screen.getByText(/no hay cargos nuevos que crear/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Confirmar generación" })).toBeDisabled();
  });

  it("says the rows were cut when the server truncated them", () => {
    renderWithAdminI18n(<AccrualView {...props({ result: ready({ itemsTruncated: true }) })} />);

    expect(screen.getByText(/Se muestran los primeros 500 renglones/)).toBeInTheDocument();
  });

  it("tells the empty state when the preview has no rows", async () => {
    const user = userEvent.setup();
    const onPreview = vi.fn();
    renderWithAdminI18n(<AccrualView {...props({ onPreview, result: ready({ items: [], toCreate: 0, errors: 0, errorsByCode: {}, totals: [], studentsEvaluated: 0 }) })} />);

    expect(screen.getByText("La vista previa no tiene renglones")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Calcular de nuevo" }));
    expect(onPreview).toHaveBeenCalledOnce();
  });

  it("asks for the preview with the button and with the form, only when a plan and a period are chosen", async () => {
    const user = userEvent.setup();
    const onPreview = vi.fn();
    const { rerender } = renderWithAdminI18n(<AccrualView {...props({ onPreview, canPreview: false, result: { kind: "idle" } })} />);

    expect(screen.getByRole("button", { name: "Calcular vista previa" })).toBeDisabled();
    expect(screen.getByText("Elija un plan y un período")).toBeInTheDocument();
    rerender(<AccrualView {...props({ onPreview, result: { kind: "idle" } })} />);
    await user.click(screen.getByRole("button", { name: "Calcular vista previa" }));

    expect(onPreview).toHaveBeenCalledOnce();
  });

  it("reports a change of the plan and of the period", async () => {
    const user = userEvent.setup();
    const base = props({ result: { kind: "idle" } });
    renderWithAdminI18n(<AccrualView {...base} />);

    await user.selectOptions(screen.getByRole("combobox", { name: "Período" }), "2027-02, vence el 5 feb 2027");

    expect(base.periods.onChange).toHaveBeenCalledWith("2027-02-01");
    expect(screen.getByRole("combobox", { name: "Plan de cobro" })).toHaveValue("p1");
  });

  it("shows a skeleton and a status while the preview is calculated, and blocks a second ask", () => {
    renderWithAdminI18n(<AccrualView {...props({ previewing: true, result: { kind: "loading" } })} />);

    expect(screen.getByText("Calculando la vista previa.").closest("[aria-busy]")).toHaveAttribute("aria-busy", "true");
    expect(screen.getByRole("button", { name: /Calcular vista previa/ })).toHaveAttribute("aria-busy", "true");
  });

  it("shows the error of the preview with a retry", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderWithAdminI18n(<AccrualView {...props({ result: { kind: "error", description: "No tiene permiso para realizar esta acción.", onRetry } })} />);

    expect(screen.getByText("No se pudo calcular la vista previa")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it("shows the plans loading as skeletons, and no form", () => {
    renderWithAdminI18n(<AccrualView {...props({ plans: { state: { kind: "loading" }, value: "", options: [], onChange: vi.fn() } })} />);

    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2, name: "Generación de cargos" }).closest("section")).toHaveAttribute("aria-busy", "true");
  });

  it("shows the error of the plans with a retry", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderWithAdminI18n(<AccrualView {...props({ plans: { state: { kind: "error", description: "Sin conexión.", onRetry }, value: "", options: [], onChange: vi.fn() } })} />);

    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(screen.getByText("No se pudieron cargar los planes activos")).toBeInTheDocument();
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it("shows the empty state when no plan is active, with the way to the plans", async () => {
    const user = userEvent.setup();
    const onGoToPlans = vi.fn();
    renderWithAdminI18n(<AccrualView {...props({ onGoToPlans, plans: { state: { kind: "empty" }, value: "", options: [], onChange: vi.fn() } })} />);

    expect(screen.getByText("No hay planes activos")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Ir a los planes de cobro" }));
    expect(onGoToPlans).toHaveBeenCalledOnce();
  });

  it("tells a plan without periods, and the periods failing to load with a retry", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    const { rerender } = renderWithAdminI18n(<AccrualView {...props({ periods: { state: { kind: "empty" }, value: "", options: [], onChange: vi.fn() }, result: { kind: "idle" } })} />);
    expect(screen.getByText("Este plan no tiene períodos para generar")).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Período" })).toBeDisabled();

    rerender(<AccrualView {...props({ periods: { state: { kind: "error", description: "Sin conexión.", onRetry }, value: "", options: [], onChange: vi.fn() }, result: { kind: "idle" } })} />);
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(screen.getByText("No se pudieron cargar los períodos del plan")).toBeInTheDocument();
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it("warns when the names of the students or of the concepts did not load, with a retry", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderWithAdminI18n(<AccrualView {...props({ lookupFailure: { onRetry } })} />);

    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(screen.getByText(/No se pudieron cargar los nombres de los estudiantes/)).toBeInTheDocument();
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it("goes to the runs", async () => {
    const user = userEvent.setup();
    const onViewRuns = vi.fn();
    renderWithAdminI18n(<AccrualView {...props({ onViewRuns })} />);

    await user.click(screen.getByRole("button", { name: "Ver ejecuciones" }));

    expect(onViewRuns).toHaveBeenCalledOnce();
  });
});
