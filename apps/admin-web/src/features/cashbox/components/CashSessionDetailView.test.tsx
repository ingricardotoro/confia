import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { cashMovement, cashSessionBody } from "../../../test/contract-responses.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { CashSessionDetailView, type CashSessionDetailViewProps } from "./CashSessionDetailView.tsx";

// States. AW57, AW58: one session rendered with props alone. Loading: a skeleton that announces the read. Empty: a session with no movements says so and offers the
// way back. Error: the load failure with its retry, and a session that is not there or not allowed, with none. Success: the report with its summary, its movements, its
// print action, the approval for a close that awaits one, the notice of the last action and the signature lines that only print.
const CLOSE = {
  id: "00000000-0000-4000-8000-0000000c0aa1",
  closedAt: "2026-11-19T18:00:00Z",
  closedBy: "00000000-0000-4000-8000-0000000000a1",
  declaredAmount: { amount: "540.00", currency: "HNL" },
  expectedAmount: { amount: "550.00", currency: "HNL" },
  differenceAmount: { amount: "-10.00", currency: "HNL" },
  differenceReason: "Billete de cien contado dos veces por error",
};

function props(override: Partial<CashSessionDetailViewProps> = {}): CashSessionDetailViewProps {
  return {
    state: { kind: "ready", session: cashSessionBody({ status: "pending_approval", close: CLOSE, movements: [cashMovement({ notes: "Cambio de la mañana" })] }) },
    backHref: "/cashbox/sessions",
    onBack: vi.fn(),
    onPrint: vi.fn(),
    ...override,
  };
}

describe("CashSessionDetailView", () => {
  it("AW57: shows the status beside the title, the summary, the movements and the way back as the breadcrumb", () => {
    renderWithAdminI18n(<CashSessionDetailView {...props()} />);

    expect(screen.getByRole("heading", { level: 2, name: /Sesión de caja del/ })).toBeInTheDocument();
    expect(screen.getAllByText("Pendiente de aprobación").length).toBeGreaterThan(1);
    expect(screen.getByText("Cambio de la mañana")).toBeInTheDocument();
    expect(screen.getByRole("navigation", { name: "Ruta de navegación" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Sesiones de caja" })).toHaveAttribute("href", "/cashbox/sessions");
  });

  it("offers to print the report and to approve the close that awaits it", async () => {
    const user = userEvent.setup();
    const onPrint = vi.fn();
    const onApprove = vi.fn();
    renderWithAdminI18n(<CashSessionDetailView {...props({ onPrint, onApprove })} />);

    await user.click(screen.getByRole("button", { name: "Imprimir reporte" }));
    await user.click(screen.getByRole("button", { name: "Aprobar cierre" }));

    expect(onPrint).toHaveBeenCalledOnce();
    expect(onApprove).toHaveBeenCalledOnce();
  });

  it("has the two signature lines, which only print, and leaves the screen actions out of the print", () => {
    renderWithAdminI18n(<CashSessionDetailView {...props({ onApprove: vi.fn() })} />);

    expect(screen.getByText("Firma del cajero").className).toContain("border-t");
    expect(screen.getByText("Firma del cajero").parentElement?.className).toContain("print:grid");
    expect(screen.getByRole("button", { name: "Imprimir reporte" }).parentElement?.className).toContain("print:hidden");
  });

  it("offers no approval when the session does not await one, and no signature lines for a session still open", () => {
    renderWithAdminI18n(<CashSessionDetailView {...props({ state: { kind: "ready", session: cashSessionBody() } })} />);

    expect(screen.queryByRole("button", { name: "Aprobar cierre" })).not.toBeInTheDocument();
    expect(screen.queryByText("Firma del cajero")).not.toBeInTheDocument();
  });

  it("says a session has no movements and goes back to the list", async () => {
    const user = userEvent.setup();
    const onBack = vi.fn();
    renderWithAdminI18n(<CashSessionDetailView {...props({ onBack, state: { kind: "ready", session: cashSessionBody({ status: "closed", close: CLOSE }) } })} />);

    expect(screen.getByText("Todavía no hay movimientos")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Volver a las sesiones" }));

    expect(onBack).toHaveBeenCalledOnce();
  });

  it("announces the read while it is in flight, with a skeleton and the way back", () => {
    renderWithAdminI18n(<CashSessionDetailView {...props({ state: { kind: "loading" } })} />);

    expect(screen.getByText("Cargando la sesión de caja.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Volver a las sesiones de caja/ })).toBeInTheDocument();
  });

  it("tells the failure with a retry, and a session that is not there with none", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    const { rerender } = renderWithAdminI18n(<CashSessionDetailView {...props({ state: { kind: "error", title: "No se pudo cargar la sesión de caja", description: "Intente de nuevo.", onRetry } })} />);
    await user.click(screen.getByRole("button", { name: "Reintentar" }));
    expect(onRetry).toHaveBeenCalledOnce();

    rerender(<CashSessionDetailView {...props({ state: { kind: "blocked", title: "No encontramos esta sesión de caja", description: "Vuelva a la lista." } })} />);

    expect(screen.getByText("No encontramos esta sesión de caja")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Reintentar" })).not.toBeInTheDocument();
  });

  it("puts the notice of the last action in a polite region", () => {
    renderWithAdminI18n(<CashSessionDetailView {...props({ notice: { tone: "success", message: "El cierre quedó aprobado." } })} />);

    expect(screen.getByText("El cierre quedó aprobado.").closest("[aria-live]")).toHaveAttribute("aria-live", "polite");
  });
});
