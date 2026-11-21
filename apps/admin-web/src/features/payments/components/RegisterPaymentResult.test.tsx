import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { paymentBody } from "../../../test/contract-responses.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { RegisterPaymentResult } from "./RegisterPaymentResult.tsx";

// States. AW57, AW58: the result of a registered payment rendered with props alone. Loading and error do not apply: the result exists only once the server
// answered. Success: a confirmed payment shows what the server applied to each charge and what stays in favor, verbatim; a declared transfer says that it
// has changed nothing yet and shows no allocations.
const handlers = () => ({ onNew: vi.fn(), onViewPayment: vi.fn(), onViewStatement: vi.fn() });

describe("RegisterPaymentResult", () => {
  it("AW57: shows the allocations and the remainder the server computed, and the way to go on", async () => {
    const user = userEvent.setup();
    const given = handlers();
    const payment = paymentBody({
      amount: { amount: "1500.00", currency: "HNL" },
      allocated: { amount: "1500.00", currency: "HNL" },
      unallocated: { amount: "0.00", currency: "HNL" },
      allocations: [
        { chargeId: "00000000-0000-4000-8000-0000000c0001", conceptName: "Mensualidad", periodLabel: "2027-01", dueOn: "2027-01-05", amount: { amount: "1265.00", currency: "HNL" }, allocatedAt: "2026-11-20T12:00:00Z" },
        { chargeId: "00000000-0000-4000-8000-0000000c0002", conceptName: "Mensualidad", periodLabel: "2027-02", dueOn: "2027-02-05", amount: { amount: "235.00", currency: "HNL" }, allocatedAt: "2026-11-20T12:00:00Z" },
      ],
    });
    renderWithAdminI18n(<RegisterPaymentResult payment={payment} {...given} />);

    expect(screen.getByRole("heading", { name: "Pago registrado" })).toBeInTheDocument();
    const table = screen.getByRole("table", { name: /Cargos a los que se aplicó el pago/ });
    expect(within(table).getByRole("row", { name: /2027-01/ })).toHaveTextContent("1,265.00");
    expect(within(table).getByRole("row", { name: /2027-02/ })).toHaveTextContent("235.00");
    await user.click(screen.getByRole("button", { name: "Registrar otro pago" }));
    await user.click(screen.getByRole("button", { name: "Ver el pago" }));
    await user.click(screen.getByRole("button", { name: "Ver el estado de cuenta" }));
    expect(given.onNew).toHaveBeenCalled();
    expect(given.onViewPayment).toHaveBeenCalled();
    expect(given.onViewStatement).toHaveBeenCalled();
  });

  it("AW57: says a declared transfer waits for its confirmation and shows no allocations", () => {
    renderWithAdminI18n(<RegisterPaymentResult payment={paymentBody({ method: "BANK_TRANSFER", status: "declared", allocations: [] })} {...handlers()} />);

    expect(screen.getByRole("heading", { name: "Transferencia declarada" })).toBeInTheDocument();
    expect(screen.getByText(/No cambia el saldo del estudiante/)).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });
});
