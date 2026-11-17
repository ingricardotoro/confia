import { fireEvent, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { taxRateDetail, taxRatePeriod } from "../../../test/contract-responses.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { TaxRateDetailView, type TaxRateDetailViewProps } from "./TaxRateDetailView.tsx";

// States. AW57, AW58: the detail of a tax rate rendered with props alone. Loading: `state.kind = "loading"`, a skeleton
// with the shape of the page. Error: `state.kind = "error"`, a message with a retry. Empty: a rate with no validity,
// with the way forward. Success: a rate with a closed validity and the open one that replaced it, and an inactive rate
// that takes no new validity. The percentages are fictitious.
const closed = taxRatePeriod({ id: "00000000-0000-4000-8000-0000000000f1", ratePercent: "7.5000", validFrom: "2027-01-01", validTo: "2027-06-30" });
const open = taxRatePeriod({ id: "00000000-0000-4000-8000-0000000000f2", ratePercent: "12.3400", validFrom: "2027-07-01", validTo: null });

function props(override: Partial<TaxRateDetailViewProps> = {}): TaxRateDetailViewProps {
  return {
    state: {
      kind: "ready",
      rate: taxRateDetail({ code: "IMP-A", name: "Impuesto de prueba A", periods: [closed, open] }),
      periods: [open, closed],
    },
    changingStatus: false,
    backHref: "/catalog/tax-rates",
    onBack: vi.fn(),
    onAddPeriod: vi.fn(),
    onToggleStatus: vi.fn(),
    ...override,
  };
}

describe("TaxRateDetailView", () => {
  it("shows the data of the rate and the history of its validities, newest first, each percentage as the server sent it", () => {
    renderWithAdminI18n(<TaxRateDetailView {...props()} />);

    expect(screen.getByRole("heading", { level: 2, name: "Impuesto de prueba A" })).toBeInTheDocument();
    const summary = within(screen.getByText("Código").closest("dl") as HTMLElement);
    expect(summary.getByText("IMP-A")).toBeInTheDocument();
    expect(summary.getByText("Activo")).toBeInTheDocument();
    const table = screen.getByRole("table", { name: "Vigencias de Impuesto de prueba A" });
    const [, newest, oldest] = within(table).getAllByRole("row");
    expect(within(newest as HTMLElement).getByText("12.3400 %")).toBeInTheDocument();
    expect(within(newest as HTMLElement).getByText("Abierta")).toBeInTheDocument();
    expect(within(newest as HTMLElement).getByText("Sin fecha de fin")).toBeInTheDocument();
    expect(within(oldest as HTMLElement).getByText("7.5000 %")).toBeInTheDocument();
    expect(within(oldest as HTMLElement).getByText("Cerrada")).toBeInTheDocument();
  });

  it("aligns the percentage to the end, in tabular figures", () => {
    renderWithAdminI18n(<TaxRateDetailView {...props()} />);

    const cell = screen.getByText("12.3400 %");
    expect(cell.className).toContain("tabular-nums");
    expect(cell.closest("td")?.className).toMatch(/text-end|text-right/);
  });

  it("the loading state is a busy region with no data", () => {
    renderWithAdminI18n(<TaxRateDetailView {...props({ state: { kind: "loading" } })} />);

    expect(screen.getByRole("region", { name: "Tasas de impuesto" })).toHaveAttribute("aria-busy", "true");
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Volver a las tasas de impuesto" })).toBeInTheDocument();
  });

  it("the error state says what failed and retries", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderWithAdminI18n(<TaxRateDetailView {...props({ state: { kind: "error", title: "No se pudo cargar la tasa de impuesto", description: "Sin conexión.", onRetry } })} />);

    expect(screen.getByText("No se pudo cargar la tasa de impuesto")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it("the empty state of a rate with no validity offers the first one", async () => {
    const user = userEvent.setup();
    const onAddPeriod = vi.fn();
    renderWithAdminI18n(<TaxRateDetailView {...props({ onAddPeriod, state: { kind: "ready", rate: taxRateDetail(), periods: [] } })} />);

    expect(screen.getByText("Esta tasa todavía no tiene vigencias")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Registrar la primera vigencia" }));

    expect(onAddPeriod).toHaveBeenCalledTimes(1);
  });

  it("reports the new validity, the deactivation and the way back", async () => {
    const user = userEvent.setup();
    const handlers = { onAddPeriod: vi.fn(), onToggleStatus: vi.fn(), onBack: vi.fn() };
    renderWithAdminI18n(<TaxRateDetailView {...props(handlers)} />);

    await user.click(screen.getByRole("button", { name: "Nueva vigencia" }));
    await user.click(screen.getByRole("button", { name: "Desactivar tasa Impuesto de prueba A" }));
    fireEvent.click(screen.getByRole("link", { name: "Volver a las tasas de impuesto" }), { metaKey: true });
    expect(handlers.onBack).not.toHaveBeenCalled();
    await user.click(screen.getByRole("link", { name: "Volver a las tasas de impuesto" }));

    expect(handlers.onAddPeriod).toHaveBeenCalledTimes(1);
    expect(handlers.onToggleStatus).toHaveBeenCalledTimes(1);
    expect(handlers.onBack).toHaveBeenCalledTimes(1);
  });

  it("an inactive rate says so, offers to activate it and takes no new validity, and its empty state offers to activate it", async () => {
    const user = userEvent.setup();
    const onToggleStatus = vi.fn();
    renderWithAdminI18n(
      <TaxRateDetailView {...props({ onToggleStatus, state: { kind: "ready", rate: taxRateDetail({ name: "Impuesto B", isActive: false }), periods: [] } })} />,
    );

    expect(screen.getByText(/está inactiva y no admite vigencias nuevas/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Activar tasa Impuesto B" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Nueva vigencia" })).toBeDisabled();
    expect(screen.queryByRole("button", { name: "Registrar la primera vigencia" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Activar tasa" }));

    expect(onToggleStatus).toHaveBeenCalledTimes(1);
  });

  it("tells the result of the last action in its region and waits while the state is being saved", () => {
    renderWithAdminI18n(<TaxRateDetailView {...props({ changingStatus: true, notice: { tone: "error", message: "El registro cambió.", traceId: "t-9" } })} />);

    expect(screen.getByText("El registro cambió.")).toBeInTheDocument();
    expect(screen.getByText("Identificador para soporte: t-9")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Desactivar tasa Impuesto de prueba A" })).toHaveAttribute("aria-busy", "true");
  });
});
