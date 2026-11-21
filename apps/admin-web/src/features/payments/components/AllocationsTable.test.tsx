import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { paymentBody } from "../../../test/contract-responses.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { AllocationsTable } from "./AllocationsTable.tsx";

// States. AW57, AW58: the allocations of a payment rendered with props alone. It holds no request, so loading and error do not apply (the screen
// that owns it has them). Empty: a payment with nothing to cover says so in words and offers the statement. Success: each charge with the amount the
// server applied, verbatim.
const TWO = [
  { chargeId: "00000000-0000-4000-8000-0000000c0001", conceptName: "Mensualidad", periodLabel: "2027-01", dueOn: "2027-01-05", amount: { amount: "1265.00", currency: "HNL" }, allocatedAt: "2026-11-20T12:00:00Z" },
  { chargeId: "00000000-0000-4000-8000-0000000c0002", conceptCode: "MAT", periodLabel: "2027-02", amount: { amount: "235.00", currency: "HNL" }, allocatedAt: "2026-11-20T12:00:00Z" },
];

describe("AllocationsTable", () => {
  it("AW57: shows each allocation as the server sent it, and falls back to the code or to a word for what the server left out", () => {
    renderWithAdminI18n(<AllocationsTable allocations={TWO} emptyAction={{ label: "Ver", onAction: vi.fn() }} />);

    const first = within(screen.getByRole("row", { name: /2027-01/ }));
    expect(first.getByText("Mensualidad")).toBeInTheDocument();
    expect(first.getAllByText(/1,265\.00/).length).toBeGreaterThan(0);
    const second = within(screen.getByRole("row", { name: /2027-02/ }));
    expect(second.getByText("MAT")).toBeInTheDocument();
    expect(second.getAllByText(/235\.00/).length).toBeGreaterThan(0);
  });

  it("AW58: says in words that nothing was covered and offers the way forward", async () => {
    const user = userEvent.setup();
    const onAction = vi.fn();
    renderWithAdminI18n(<AllocationsTable allocations={paymentBody({ allocations: [] }).allocations} emptyAction={{ label: "Ver el estado de cuenta", onAction }} />);

    expect(screen.getByText("El pago no se aplicó a ningún cargo")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Ver el estado de cuenta" }));
    expect(onAction).toHaveBeenCalled();
  });
});
