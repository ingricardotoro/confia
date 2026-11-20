import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { cashSessionBody } from "../../../test/contract-responses.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { CashSessionSummary } from "./CashSessionSummary.tsx";

// States. AW57, AW58: the summary of a session rendered with props alone. Loading, empty and error belong to the screen that holds it. Success: an open session
// (float and expected cash), a closed one (counted cash, the difference the server computed, its reason) and an approved one.
const CLOSE = {
  id: "00000000-0000-4000-8000-0000000c0aa1",
  closedAt: "2026-11-19T18:00:00Z",
  closedBy: "00000000-0000-4000-8000-0000000000a1",
  declaredAmount: { amount: "540.00", currency: "HNL" },
  expectedAmount: { amount: "550.00", currency: "HNL" },
  differenceAmount: { amount: "-10.00", currency: "HNL" },
  differenceReason: "Billete de cien contado dos veces por error",
};

describe("CashSessionSummary", () => {
  it("AW57: shows the float and the expected cash of an open session, and no count", () => {
    renderWithAdminI18n(<CashSessionSummary session={cashSessionBody({ expectedAmount: { amount: "550.00", currency: "HNL" } })} />);

    expect(screen.getByText("Fondo inicial")).toBeInTheDocument();
    expect(screen.getByText("Efectivo esperado")).toBeInTheDocument();
    expect(screen.getAllByText(/550\.00/).length).toBeGreaterThan(0);
    expect(screen.queryByText("Efectivo contado")).not.toBeInTheDocument();
    expect(screen.queryByText("Cajero")).not.toBeInTheDocument();
  });

  it("shows the count, the difference as the server computed it with its sign, and the reason, and names the cashier on request", () => {
    renderWithAdminI18n(<CashSessionSummary showCashier session={cashSessionBody({ status: "pending_approval", close: CLOSE })} />);

    expect(screen.getByText("Efectivo contado")).toBeInTheDocument();
    expect(screen.getAllByText(/540\.00/).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/-.*10\.00/).length).toBeGreaterThan(0);
    expect(screen.getByText("Billete de cien contado dos veces por error")).toBeInTheDocument();
    expect(screen.getByText("00000000-0000-4000-8000-0000000000a1")).toBeInTheDocument();
  });

  it("shows the approval with its note once the server holds one", () => {
    renderWithAdminI18n(
      <CashSessionSummary
        session={cashSessionBody({
          status: "closed",
          close: CLOSE,
          approval: { id: "00000000-0000-4000-8000-0000000c0bb1", approvedAt: "2026-11-19T19:00:00Z", approvedBy: "00000000-0000-4000-8000-0000000000b2", note: "Revisado con el cajero" },
        })}
      />,
    );

    expect(screen.getByText("Nota de la aprobación")).toBeInTheDocument();
    expect(screen.getByText("Revisado con el cajero")).toBeInTheDocument();
  });
});
