import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { studentCharge } from "../../../test/contract-responses.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { PayableChargesPanel, type PayableChargesPanelProps } from "./PayableChargesPanel.tsx";

// States. AW57, AW58: what a student owes rendered with props alone. Loading: skeleton rows with a polite status. Error: the message and a retry.
// Empty: no pending charge, said in words with a way forward. Success: the charges with what each has paid and owes, and the balance, as the server
// sent them. The balance has its own states (loading, unavailable, ready).
const PAGING = { page: 0, size: 10, total: 1, onPageChange: vi.fn(), busy: false };
const CHARGE = studentCharge({
  periodLabel: "2027-01",
  conceptName: "Mensualidad",
  total: { amount: "1265.00", currency: "HNL" },
  paid: { amount: "235.00", currency: "HNL" },
  outstanding: { amount: "1030.00", currency: "HNL" },
});

function props(override: Partial<PayableChargesPanelProps> = {}): PayableChargesPanelProps {
  return {
    charges: { kind: "ready", rows: [CHARGE], pagination: PAGING },
    balance: { kind: "ready", amount: { amount: "1030.00", currency: "HNL" } },
    onViewCharges: vi.fn(),
    ...override,
  };
}

describe("PayableChargesPanel", () => {
  it("AW57: shows the charge with its total, what it has paid and what it owes, and the balance, as the server sent them", () => {
    renderWithAdminI18n(<PayableChargesPanel {...props()} />);

    const row = within(screen.getByRole("row", { name: /2027-01/ }));
    expect(row.getAllByText(/1,265\.00/).length).toBeGreaterThan(0);
    expect(row.getAllByText(/235\.00/).length).toBeGreaterThan(0);
    expect(row.getAllByText(/1,030\.00/).length).toBeGreaterThan(0);
    expect(screen.getByText(/Saldo del estudiante/)).toBeInTheDocument();
  });

  it("AW58: shows a skeleton with a polite status while it loads", () => {
    renderWithAdminI18n(<PayableChargesPanel {...props({ charges: { kind: "loading" }, balance: { kind: "loading" } })} />);

    expect(screen.getByRole("status")).toHaveTextContent("Cargando los cargos del estudiante.");
  });

  it("AW58: tells the failure and retries", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderWithAdminI18n(<PayableChargesPanel {...props({ charges: { kind: "error", description: "Sin conexión.", onRetry }, balance: { kind: "unavailable" } })} />);

    expect(screen.getByText("No se pudieron cargar los cargos del estudiante")).toBeInTheDocument();
    expect(screen.getByText(/No disponible/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /Reintentar/ }));
    expect(onRetry).toHaveBeenCalled();
  });

  it("AW58: says in words that nothing is pending and offers the charges of the student", async () => {
    const user = userEvent.setup();
    const onViewCharges = vi.fn();
    renderWithAdminI18n(<PayableChargesPanel {...props({ charges: { kind: "ready", rows: [], pagination: { ...PAGING, total: 0 } }, onViewCharges })} />);

    expect(screen.getByText("El estudiante no tiene cargos pendientes")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Ver los cargos del estudiante" }));
    expect(onViewCharges).toHaveBeenCalled();
  });

  it("AW57: pages the charges as the server pages them and asks for the next page", async () => {
    const user = userEvent.setup();
    const onPageChange = vi.fn();
    renderWithAdminI18n(<PayableChargesPanel {...props({ charges: { kind: "ready", rows: [CHARGE], pagination: { ...PAGING, total: 25, onPageChange } } })} />);

    await user.click(screen.getByRole("button", { name: /Siguiente/ }));
    expect(onPageChange).toHaveBeenCalledWith(1);
  });
});
