import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { integrityRun } from "../../../test/contract-responses.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { IntegrityRunDetailView, type IntegrityRunDetailViewProps } from "./IntegrityRunDetailView.tsx";

// States. AW57, AW58: one integrity run rendered with props alone. Loading: skeletons that announce the read. Error: the error state with
// a retry. Blocked: a run that is not in the institution, with no retry. Empty: a run with no discrepancies, with a way back. Success: the
// discrepancies with what the charges say and what the ledger holds, as the server sent them.
function props(override: Partial<IntegrityRunDetailViewProps> = {}): IntegrityRunDetailViewProps {
  return { state: { kind: "ready", detail: { run: integrityRun({ status: "DISCREPANCIES", discrepancyCount: 1 }), findings: [] } }, backHref: "/charges/integrity", onBack: vi.fn(), ...override };
}

describe("IntegrityRunDetailView", () => {
  it("shows each discrepancy with its kind in words, the ids, and the amounts of both sides, and a text where a side has none", () => {
    const findings = [
      {
        kind: "CHARGE_TOTAL_MISMATCH" as const,
        chargeId: "00000000-0000-4000-8000-0000000d0001",
        ledgerTransactionId: "00000000-0000-4000-8000-0000000e0001",
        expected: { amount: "1150.00", currency: "HNL" },
        actual: { amount: "1100.00", currency: "HNL" },
      },
      { kind: "UNBALANCED_TRANSACTION" as const, ledgerTransactionId: "00000000-0000-4000-8000-0000000e0002" },
    ];
    renderWithAdminI18n(<IntegrityRunDetailView {...props({ state: { kind: "ready", detail: { run: integrityRun({ status: "DISCREPANCIES", discrepancyCount: 2 }), findings } } })} />);

    const rows = screen.getAllByRole("row");
    const first = within(rows[1] as HTMLElement);
    expect(first.getByText("El asiento no coincide con el total del cargo")).toBeInTheDocument();
    expect(first.getByText("00000000-0000-4000-8000-0000000d0001")).toBeInTheDocument();
    expect(first.getByText("1,150.00 lempiras hondureños")).toBeInTheDocument();
    expect(first.getByText("1,100.00 lempiras hondureños")).toBeInTheDocument();
    const second = within(rows[2] as HTMLElement);
    expect(second.getByText("Asiento descuadrado")).toBeInTheDocument();
    expect(second.getAllByText("No aplica")).toHaveLength(3);
  });

  it("names a kind the server adds later as an unrecognized discrepancy, never as a blank", () => {
    const findings = [{ kind: "SOMETHING_NEW" as never }];
    renderWithAdminI18n(<IntegrityRunDetailView {...props({ state: { kind: "ready", detail: { run: integrityRun({ status: "DISCREPANCIES" }), findings } } })} />);

    expect(screen.getByText("Discrepancia no reconocida")).toBeInTheDocument();
  });

  it("says a run with no discrepancies found the charges and the ledger in agreement, and goes back", async () => {
    const user = userEvent.setup();
    const onBack = vi.fn();
    renderWithAdminI18n(<IntegrityRunDetailView {...props({ onBack, state: { kind: "ready", detail: { run: integrityRun(), findings: [] } } })} />);

    expect(screen.getByText(/los cargos y el libro mayor coinciden/)).toBeInTheDocument();
    await user.click(screen.getAllByRole("button", { name: "Volver a las comprobaciones" })[0] as HTMLElement);

    expect(onBack).toHaveBeenCalledOnce();
  });

  it("announces the read while it loads", () => {
    renderWithAdminI18n(<IntegrityRunDetailView {...props({ state: { kind: "loading" } })} />);

    expect(screen.getByRole("status")).toHaveTextContent("Cargando la comprobación.");
  });

  it("tells a failed read with a retry", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderWithAdminI18n(<IntegrityRunDetailView {...props({ state: { kind: "error", title: "No se pudo cargar la comprobación", description: "Identificador para soporte: X", onRetry } })} />);

    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(onRetry).toHaveBeenCalledOnce();
  });

  it("tells a run that is not available as a warning with no retry to press", () => {
    renderWithAdminI18n(<IntegrityRunDetailView {...props({ state: { kind: "blocked", title: "No encontramos esta comprobación", description: "No existe." } })} />);

    expect(screen.getByText("No encontramos esta comprobación")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Reintentar" })).not.toBeInTheDocument();
  });
});
