import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { paymentBody } from "../../../test/contract-responses.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { PaymentSummary } from "./PaymentSummary.tsx";

// States. AW57, AW58: the facts of a payment rendered with props alone. It holds no request, so loading, empty and error do not apply. Success: a
// confirmed payment shows what it applied and what stayed in favor; a declared transfer shows neither (it has applied nothing); a rejected one shows
// its decision and reason.
describe("PaymentSummary", () => {
  it("AW57: shows what a confirmed cash payment applied and what stays in favor, as the server sent them, and no bank data", () => {
    renderWithAdminI18n(<PaymentSummary payment={paymentBody({ unallocated: { amount: "235.00", currency: "HNL" } })} />);

    expect(screen.getByText("Confirmado")).toBeInTheDocument();
    expect(screen.getByText("Aplicado a cargos")).toBeInTheDocument();
    expect(screen.getAllByText(/235\.00/).length).toBeGreaterThan(0);
    expect(screen.queryByText("Referencia")).not.toBeInTheDocument();
  });

  it("AW57: shows the reference and the bank of a declared transfer, and leaves out what it has not applied", () => {
    renderWithAdminI18n(<PaymentSummary payment={paymentBody({ method: "BANK_TRANSFER", status: "declared", reference: "AB1234", bankName: "Banco Atlántida", allocations: [] })} />);

    expect(screen.getByText("AB1234")).toBeInTheDocument();
    expect(screen.getByText("Banco Atlántida")).toBeInTheDocument();
    expect(screen.queryByText("Aplicado a cargos")).not.toBeInTheDocument();
    expect(screen.queryByText("Queda a favor del estudiante")).not.toBeInTheDocument();
  });

  it("AW57: shows the rejection and its reason", () => {
    renderWithAdminI18n(
      <PaymentSummary
        payment={paymentBody({
          method: "BANK_TRANSFER",
          status: "rejected",
          decision: { id: "00000000-0000-4000-8000-0000000f0001", kind: "rejected", reason: "La referencia no aparece en el banco", decidedAt: "2026-11-20T15:00:00Z", decidedBy: "00000000-0000-4000-8000-0000000000a2" },
        })}
      />,
    );

    expect(screen.getByText(/Rechazada el/)).toBeInTheDocument();
    expect(screen.getByText("La referencia no aparece en el banco")).toBeInTheDocument();
  });
});
