import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { statementLine, statementSection } from "../../../test/contract-responses.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { StatementSectionView } from "./StatementSectionView.tsx";

// States. AW57, AW58: one currency of the statement rendered with props alone. Success: the balances and the lines as the server
// sent them, with the running balance of each line. Empty: a period with no lines still shows its balances and a way to change the
// period. Loading and error belong to the page that reads the statement (StatementView): a section is only drawn once it exists.
// A balance in favor of the student is told in words, never by its sign or its colour alone.
const MONEY = (amount: string) => ({ amount, currency: "HNL" });

function renderSection(section = statementSection(), onPageChange = vi.fn(), busy = false) {
  return renderWithAdminI18n(<StatementSectionView section={section} from="2026-11-17" to="2027-11-16" busy={busy} onPageChange={onPageChange} />);
}

describe("StatementSectionView", () => {
  it("shows the opening and closing balances the server sent, worded as a debt, with the period they belong to", () => {
    renderSection(statementSection({ openingBalance: MONEY("200.00"), closingBalance: MONEY("1700.00") }));

    const opening = screen.getByText("Saldo inicial").parentElement as HTMLElement;
    expect(within(opening).getByText("200.00 lempiras hondureños")).toBeInTheDocument();
    expect(within(opening).getByText(/Antes del 17 nov 2026\./)).toBeInTheDocument();
    const closing = screen.getByText("Saldo final").parentElement as HTMLElement;
    expect(within(closing).getByText("1,700.00 lempiras hondureños")).toBeInTheDocument();
    expect(within(closing).getByText(/Al 16 nov 2027\./)).toBeInTheDocument();
    expect(within(closing).getByText(/El estudiante debe este monto\./)).toBeInTheDocument();
    expect(screen.queryByText("Saldo a favor")).not.toBeInTheDocument();
  });

  it("words a negative closing balance as «Saldo a favor» in text, beside the figure the server sent", () => {
    renderSection(statementSection({ openingBalance: MONEY("0.00"), closingBalance: MONEY("-300.00"), lines: [statementLine({ balance: MONEY("-300.00") })] }));

    const closing = screen.getByText("Saldo final").parentElement as HTMLElement;
    expect(within(closing).getByText("Saldo a favor")).toBeInTheDocument();
    expect(within(closing).getByText(/Saldo a favor del estudiante\./)).toBeInTheDocument();
    expect(within(closing).getByText(/-300\.00 lempiras hondureños/)).toBeInTheDocument();
  });

  it("says a zero balance has nothing pending", () => {
    renderSection(statementSection({ openingBalance: MONEY("0.00"), closingBalance: MONEY("0.00"), lines: [] }));

    expect(screen.getAllByText(/Sin saldo pendiente\./)).toHaveLength(2);
  });

  it("lists a charge with its concept, period and due date, and a debit with a dash and a spoken word in the credit column", () => {
    renderSection();

    const row = screen.getByRole("row", { name: /Colegiatura, 2027-02/ });
    expect(within(row).getByText("Colegiatura, 2027-02")).toBeInTheDocument();
    expect(within(row).getByText("Cargo", { selector: "span:not([class*='sr-only'])" })).toBeInTheDocument();
    expect(within(row).getByText("1 feb 2027")).toBeInTheDocument();
    expect(within(row).getByText("10 feb 2027")).toBeInTheDocument();
    expect(within(row).getAllByText("1,500.00 lempiras hondureños")).toHaveLength(2);
    expect(within(row).getByText("Sin importe")).toBeInTheDocument();
  });

  it("lists a movement that is not a charge by its kind and reason, with no due date", () => {
    const payment = statementLine({
      kind: "PAYMENT",
      charge: undefined,
      reason: "Pago en caja",
      effectiveOn: "2027-02-12",
      debit: MONEY("0.00"),
      credit: MONEY("500.00"),
      balance: MONEY("1000.00"),
    });
    renderSection(statementSection({ lines: [statementLine(), payment], closingBalance: MONEY("1000.00") }));

    const row = screen.getByRole("row", { name: /Pago: Pago en caja/ });
    expect(within(row).getByText("Abono")).toBeInTheDocument();
    expect(within(row).getByText("No aplica")).toBeInTheDocument();
    expect(within(row).getByText("1,000.00 lempiras hondureños")).toBeInTheDocument();
  });

  it("names a concept the directory does not know as not available", () => {
    const line = statementLine();
    const charge = line.charge;
    if (charge === undefined) throw new Error("the fixture has a charge");
    renderSection(statementSection({ lines: [{ ...line, charge: { ...charge, conceptName: null, conceptCode: null } }] }));

    expect(screen.getByText("No disponible, 2027-02")).toBeInTheDocument();
  });

  it("shows the balances and a way to change the period when the period has no lines", async () => {
    const user = userEvent.setup();
    renderSection(statementSection({ lines: [], totalLines: 0, closingBalance: MONEY("0.00") }));

    expect(screen.getByText("Sin movimientos en este período")).toBeInTheDocument();
    expect(screen.getByText("Saldo final")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Cambiar las fechas" }));
    expect(screen.queryByRole("navigation", { name: /Páginas/ })).not.toBeInTheDocument();
  });

  it("pages the lines through the server: it asks for the next page, zero based", async () => {
    const user = userEvent.setup();
    const onPageChange = vi.fn();
    renderSection(statementSection({ totalLines: 60, size: 25, page: 0 }), onPageChange);

    await user.click(screen.getByRole("button", { name: /Siguiente/ }));

    expect(onPageChange).toHaveBeenCalledWith(1);
  });

  it("blocks the pagination while a page is being read", () => {
    renderSection(statementSection({ totalLines: 60, size: 25, page: 0 }), vi.fn(), true);

    expect(screen.getByRole("button", { name: /Siguiente/ })).toBeDisabled();
  });
});
