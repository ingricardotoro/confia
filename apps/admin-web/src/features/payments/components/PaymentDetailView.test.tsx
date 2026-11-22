import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { paymentBody } from "../../../test/contract-responses.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { PaymentDetailView, type PaymentDetailViewProps } from "./PaymentDetailView.tsx";

// States. AW57, AW58: one payment rendered with props alone. Loading: skeleton with a polite status. Error: title, description and a retry. Blocked: a payment
// that is not the person or a missing permission, with no retry. Empty does not apply: a payment always has its data. Success: a confirmed payment with its
// allocations, a declared transfer with the decision actions, and a rejected one with its note.
const DECLARED = paymentBody({ method: "BANK_TRANSFER", status: "declared", reference: "AB1234", allocations: [] });

function props(override: Partial<PaymentDetailViewProps> = {}): PaymentDetailViewProps {
  return { state: { kind: "ready", payment: paymentBody() }, backHref: "/payments", onBack: vi.fn(), onViewStatement: vi.fn(), ...override };
}

describe("PaymentDetailView", () => {
  it("AW57: shows a confirmed payment with its allocations and no decision actions", () => {
    renderWithAdminI18n(<PaymentDetailView {...props()} />);

    expect(screen.getByRole("heading", { level: 2, name: /Pago del/ })).toBeInTheDocument();
    expect(screen.getByRole("table", { name: /Cargos a los que se aplicó el pago/ })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Confirmar" })).not.toBeInTheDocument();
  });

  it("AW57: offers Confirm and Reject on a declared transfer, says it changed nothing, and shows no allocations", async () => {
    const user = userEvent.setup();
    const onConfirm = vi.fn();
    const onReject = vi.fn();
    renderWithAdminI18n(<PaymentDetailView {...props({ state: { kind: "ready", payment: DECLARED }, onConfirm, onReject })} />);

    expect(screen.getByText(/Esta transferencia está pendiente de confirmación/)).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Confirmar" }));
    await user.click(screen.getByRole("button", { name: "Rechazar" }));
    expect(onConfirm).toHaveBeenCalled();
    expect(onReject).toHaveBeenCalled();
  });

  it("AW57: says that a rejected transfer left nothing in the ledger", () => {
    const rejected = paymentBody({
      method: "BANK_TRANSFER",
      status: "rejected",
      allocations: [],
      decision: { id: "00000000-0000-4000-8000-0000000f0001", kind: "rejected", reason: "Referencia inexistente en el banco", decidedAt: "2026-11-20T15:00:00Z", decidedBy: "00000000-0000-4000-8000-0000000000a2" },
    });
    renderWithAdminI18n(<PaymentDetailView {...props({ state: { kind: "ready", payment: rejected } })} />);

    expect(screen.getByText(/Esta transferencia fue rechazada/)).toBeInTheDocument();
  });

  it("AW58: shows a skeleton with a polite status while it loads", () => {
    renderWithAdminI18n(<PaymentDetailView {...props({ state: { kind: "loading" } })} />);

    expect(screen.getByRole("status")).toHaveTextContent("Cargando el pago.");
    expect(screen.getByRole("link", { name: /Volver a los pagos/ })).toBeInTheDocument();
  });

  it("AW58: tells the failure with a retry, and a blocked payment without one", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    const { unmount } = renderWithAdminI18n(<PaymentDetailView {...props({ state: { kind: "error", title: "No se pudo cargar el pago", description: "Sin conexión.", onRetry } })} />);

    await user.click(screen.getByRole("button", { name: /Reintentar/ }));
    expect(onRetry).toHaveBeenCalled();
    unmount();

    renderWithAdminI18n(<PaymentDetailView {...props({ state: { kind: "blocked", title: "No encontramos este pago", description: "No existe." } })} />);
    expect(screen.getByText("No encontramos este pago")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Reintentar/ })).not.toBeInTheDocument();
  });
});
