import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { cashMovement, cashSessionBody } from "../../../test/contract-responses.ts";
import type { CashMovementType } from "../cashbox-types.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { MyCashboxView, type MyCashboxViewProps } from "./MyCashboxView.tsx";

// States. AW57, AW58: the cashier's own cashbox rendered with props alone. Loading: a skeleton that announces the read. Empty: no open session, so the form to open
// one (a slot), and no movements yet in an open one. Error: the load failure with its retry, and the permission that is missing, with none. Success: an open
// session with its actions, the notice of the last action, and the session that was just closed with what the server computed.
function props(override: Partial<MyCashboxViewProps> = {}): MyCashboxViewProps {
  return {
    state: { kind: "ready", session: cashSessionBody({ expectedAmount: { amount: "550.00", currency: "HNL" }, movements: [cashMovement()] }) },
    openForm: <p>formulario de apertura</p>,
    onRegister: vi.fn(),
    onClose: vi.fn(),
    onViewReport: vi.fn(),
    ...override,
  };
}

describe("MyCashboxView", () => {
  it("AW57: shows the expected cash of the open session and offers each action, with the close as the primary one", async () => {
    const user = userEvent.setup();
    const onRegister = vi.fn<(type: CashMovementType) => void>();
    const onClose = vi.fn();
    renderWithAdminI18n(<MyCashboxView {...props({ onRegister, onClose })} />);

    expect(screen.getByRole("heading", { level: 2, name: "Mi caja" })).toBeInTheDocument();
    expect(screen.getAllByText(/550\.00/).length).toBeGreaterThan(0);
    await user.click(screen.getByRole("button", { name: "Registrar retiro" }));
    await user.click(screen.getByRole("button", { name: "Registrar ajuste" }));
    await user.click(screen.getAllByRole("button", { name: "Registrar depósito" })[0] as HTMLElement);
    await user.click(screen.getByRole("button", { name: "Cerrar caja" }));

    expect(onRegister.mock.calls.map(([type]) => type)).toEqual(["withdrawal", "adjustment", "deposit"]);
    expect(onClose).toHaveBeenCalledOnce();
    expect(screen.queryByText("formulario de apertura")).not.toBeInTheDocument();
  });

  it("shows the form to open a session, and no actions, when there is none", () => {
    renderWithAdminI18n(<MyCashboxView {...props({ state: { kind: "none" } })} />);

    expect(screen.getByText("formulario de apertura")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Cerrar caja" })).not.toBeInTheDocument();
  });

  it("says in the empty movements that there is none and offers a deposit", () => {
    renderWithAdminI18n(<MyCashboxView {...props({ state: { kind: "ready", session: cashSessionBody() } })} />);

    expect(screen.getByText("Todavía no hay movimientos")).toBeInTheDocument();
  });

  it("announces the read while it is in flight, with a skeleton", () => {
    renderWithAdminI18n(<MyCashboxView {...props({ state: { kind: "loading" } })} />);

    expect(screen.getByText("Cargando su caja.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Cerrar caja" })).not.toBeInTheDocument();
  });

  it("tells the failure with a retry", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderWithAdminI18n(<MyCashboxView {...props({ state: { kind: "error", title: "No se pudo cargar su caja", description: "Intente de nuevo.", onRetry } })} />);

    expect(screen.getByText("No se pudo cargar su caja")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(onRetry).toHaveBeenCalledOnce();
  });

  it("tells a missing permission in plain words, with no retry", () => {
    renderWithAdminI18n(<MyCashboxView {...props({ state: { kind: "blocked", title: "No tiene acceso a la caja", description: "Solicítelo a un administrador." } })} />);

    expect(screen.getByText("No tiene acceso a la caja")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Reintentar" })).not.toBeInTheDocument();
  });

  it("puts the notice of the last action in a polite region", () => {
    renderWithAdminI18n(<MyCashboxView {...props({ notice: { tone: "success", message: "Se registró el depósito." } })} />);

    expect(screen.getByText("Se registró el depósito.").closest("[aria-live]")).toHaveAttribute("aria-live", "polite");
  });

  it("shows the session just closed with the difference the server computed and a way to its report", async () => {
    const user = userEvent.setup();
    const onViewReport = vi.fn();
    const closed = cashSessionBody({
      status: "pending_approval",
      close: {
        id: "00000000-0000-4000-8000-0000000c0aa1",
        closedAt: "2026-11-19T18:00:00Z",
        closedBy: "00000000-0000-4000-8000-0000000000a1",
        declaredAmount: { amount: "540.00", currency: "HNL" },
        expectedAmount: { amount: "550.00", currency: "HNL" },
        differenceAmount: { amount: "-10.00", currency: "HNL" },
        differenceReason: "Billete de cien contado dos veces por error",
      },
    });
    renderWithAdminI18n(<MyCashboxView {...props({ state: { kind: "none" }, closed, onViewReport })} />);

    expect(screen.getByText(/La caja se cerró con diferencia/)).toBeInTheDocument();
    expect(screen.getAllByText(/-.*10\.00/).length).toBeGreaterThan(0);
    await user.click(screen.getByRole("button", { name: "Ver reporte de cierre" }));

    expect(onViewReport).toHaveBeenCalledWith(closed);
  });
});
