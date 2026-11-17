import { fireEvent, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { chargePlanDetail } from "../../../test/contract-responses.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { ChargePlanDetailView, type ChargePlanDetailViewProps, type ChargePlanItemView } from "./ChargePlanDetailView.tsx";

// States. AW57, AW58: the detail of a plan rendered with props alone. Loading: `state.kind = "loading"`, a skeleton with the
// shape of the page. Error: `state.kind = "error"`, a message with a retry. Empty: a draft with no items, with the way forward.
// Success: a draft with two items, an active plan (frozen) and an archived one (for reading).
const COLEGIATURA: ChargePlanItemView = { id: "00000000-0000-4000-8000-0000000000f1", code: "COL", name: "Colegiatura", periodsCount: 10, firstPeriodOn: "2027-02-01" };
const MATRICULA: ChargePlanItemView = { id: "00000000-0000-4000-8000-0000000000f2", code: "MAT", name: "Matrícula anual", periodsCount: 1, firstPeriodOn: "2027-01-01" };

function props(override: Partial<ChargePlanDetailViewProps> = {}, plan: Parameters<typeof chargePlanDetail>[0] = {}): ChargePlanDetailViewProps {
  return {
    state: {
      kind: "ready",
      plan: chargePlanDetail({ name: "Primaria 2027", status: "DRAFT", frequency: "MONTHLY", dueDayOfMonth: 5, ...plan }),
      yearName: "Año 2027",
      referenceName: "Primer grado",
      items: [COLEGIATURA, MATRICULA],
    },
    backHref: "/charges/plans",
    onBack: vi.fn(),
    onEdit: vi.fn(),
    onActivate: vi.fn(),
    onArchive: vi.fn(),
    onAddItem: vi.fn(),
    onRemoveItem: vi.fn(),
    ...override,
  };
}

describe("ChargePlanDetailView", () => {
  it("shows the data of the plan and a table with the items: concept, periods and first period", () => {
    renderWithAdminI18n(<ChargePlanDetailView {...props()} />);

    expect(screen.getByRole("heading", { level: 2, name: "Primaria 2027" })).toBeInTheDocument();
    const summary = within(screen.getByLabelText("Datos del plan"));
    expect(summary.getByText("Año 2027")).toBeInTheDocument();
    expect(summary.getByText("Un grado")).toBeInTheDocument();
    expect(summary.getByText("Primer grado")).toBeInTheDocument();
    expect(summary.getByText("Mensual")).toBeInTheDocument();
    expect(summary.getByText("Día 5")).toBeInTheDocument();
    expect(summary.getByText("Borrador")).toBeInTheDocument();
    const table = screen.getByRole("table", { name: "Conceptos del plan Primaria 2027" });
    expect(within(table).getAllByRole("columnheader").map((header) => header.textContent)).toEqual(["Concepto", "Períodos", "Primer período", "Acciones"]);
    const first = within(within(table).getByRole("row", { name: /COL — Colegiatura/ }));
    expect(first.getByText("10")).toBeInTheDocument();
    expect(first.getByText(/feb/i)).toBeInTheDocument();
  });

  it("a draft can be edited, activated, given items and have them removed, and each button names the plan or the concept it acts on", async () => {
    const user = userEvent.setup();
    const view = props();
    renderWithAdminI18n(<ChargePlanDetailView {...view} />);

    await user.click(screen.getByRole("button", { name: "Editar plan Primaria 2027" }));
    await user.click(screen.getByRole("button", { name: "Activar plan Primaria 2027" }));
    await user.click(screen.getByRole("button", { name: "Agregar concepto" }));
    await user.click(screen.getByRole("button", { name: "Quitar Matrícula anual del plan" }));

    expect(view.onEdit).toHaveBeenCalledTimes(1);
    expect(view.onActivate).toHaveBeenCalledTimes(1);
    expect(view.onAddItem).toHaveBeenCalledTimes(1);
    expect(view.onRemoveItem).toHaveBeenCalledWith(MATRICULA);
    expect(screen.queryByRole("button", { name: /Archivar plan/ })).not.toBeInTheDocument();
    expect(screen.getByText(/Este plan es un borrador/)).toBeInTheDocument();
  });

  it("an accessible name of a button holds the text it shows", () => {
    renderWithAdminI18n(<ChargePlanDetailView {...props()} />);

    for (const button of screen.getAllByRole("button")) {
      const label = button.getAttribute("aria-label");
      if (label !== null) expect(label).toContain(button.textContent);
    }
  });

  it("an active plan is frozen: it can be renamed and archived, and has no way to add or remove items or to be activated", async () => {
    const user = userEvent.setup();
    const view = props({}, { status: "ACTIVE" });
    renderWithAdminI18n(<ChargePlanDetailView {...view} />);

    await user.click(screen.getByRole("button", { name: "Archivar plan Primaria 2027" }));

    expect(view.onArchive).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button", { name: "Editar plan Primaria 2027" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Activar plan/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Agregar concepto" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Quitar/ })).not.toBeInTheDocument();
    expect(within(screen.getByRole("table")).getAllByRole("columnheader").map((header) => header.textContent)).toEqual(["Concepto", "Períodos", "Primer período"]);
    expect(screen.getByText(/Este plan está activo y congelado/)).toBeInTheDocument();
  });

  it("an archived plan is for reading: no action changes it", () => {
    renderWithAdminI18n(<ChargePlanDetailView {...props({}, { status: "ARCHIVED" })} />);

    expect(screen.queryByRole("button", { name: /Editar|Activar|Archivar|Agregar|Quitar/ })).not.toBeInTheDocument();
    expect(screen.getByText(/Este plan está archivado/)).toBeInTheDocument();
  });

  it("names a concept that is not among the ones loaded as not available", () => {
    const view = props();
    if (view.state.kind !== "ready") throw new Error("ready");
    renderWithAdminI18n(<ChargePlanDetailView {...view} state={{ ...view.state, items: [{ id: COLEGIATURA.id, periodsCount: 3, firstPeriodOn: "2027-03-01" }] }} />);

    expect(within(screen.getByRole("row", { name: /No disponible/ })).getByText("3")).toBeInTheDocument();
  });

  it("the loading state is a busy region with no data", () => {
    renderWithAdminI18n(<ChargePlanDetailView {...props({ state: { kind: "loading" } })} />);

    expect(screen.getByRole("region", { name: "Planes de cobro" })).toHaveAttribute("aria-busy", "true");
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Volver a los planes de cobro" })).toBeInTheDocument();
  });

  it("the error state says what failed and retries", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderWithAdminI18n(<ChargePlanDetailView {...props({ state: { kind: "error", title: "No se pudo cargar el plan de cobro", description: "Intente de nuevo.", onRetry } })} />);

    expect(screen.getByText("No se pudo cargar el plan de cobro")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it("the empty state of a draft offers the first item; the one of a plan that is not a draft offers the way back", async () => {
    const user = userEvent.setup();
    const empty = (status: "DRAFT" | "ACTIVE") => {
      const view = props({}, { status });
      if (view.state.kind !== "ready") throw new Error("ready");
      return { ...view, state: { ...view.state, items: [] } };
    };
    const draft = empty("DRAFT");
    const { unmount } = renderWithAdminI18n(<ChargePlanDetailView {...draft} />);

    expect(screen.getByText("Este plan todavía no tiene conceptos")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Agregar el primer concepto" }));
    expect(draft.onAddItem).toHaveBeenCalledTimes(1);
    unmount();

    const active = empty("ACTIVE");
    renderWithAdminI18n(<ChargePlanDetailView {...active} />);
    expect(screen.queryByRole("button", { name: "Agregar el primer concepto" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Volver a los planes de cobro" }));
    expect(active.onBack).toHaveBeenCalledTimes(1);
  });

  it("warns when the names failed to load, and tells the result of the last action", () => {
    renderWithAdminI18n(
      <ChargePlanDetailView {...props({ lookupFailure: { onRetry: vi.fn() }, notice: { tone: "error", message: "El plan ya no admite este cambio.", traceId: "01J8Z4K2QF9P0X" } })} />,
    );

    expect(screen.getByText(/No se pudieron cargar los nombres de los conceptos/)).toBeInTheDocument();
    expect(screen.getByText("El plan ya no admite este cambio.")).toBeInTheDocument();
    expect(screen.getByText(/01J8Z4K2QF9P0X/)).toBeInTheDocument();
  });

  it("goes back from the link and from the breadcrumb, following a plain click only", () => {
    const onBack = vi.fn();
    renderWithAdminI18n(<ChargePlanDetailView {...props({ onBack })} />);
    const crumb = within(screen.getByRole("navigation", { name: "Ruta de navegación" })).getByRole("link", { name: "Planes de cobro" });

    fireEvent.click(crumb);

    expect(onBack).toHaveBeenCalledTimes(1);
  });
});
