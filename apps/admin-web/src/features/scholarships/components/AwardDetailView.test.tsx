import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { award } from "../../../test/contract-responses.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { AwardDetailView, type AwardDetailViewProps } from "./AwardDetailView.tsx";

// States. AW57, AW58: the detail of an award rendered with props alone. Loading: the skeleton with its status text. Error: the error state with its retry.
// Blocked: a 404 or a 403 told in words. Success: a pending award (with the decision actions it is given), an approved one, and a rejected or revoked one
// with the reason of the decision.
const DECISION = { id: "00000000-0000-4000-8000-0000000d0001", kind: "approved", decidedAt: "2026-11-20T15:00:00Z", decidedBy: "00000000-0000-4000-8000-0000000000a2" } as const;
const REASON = "El estudiante no cumple el promedio mínimo del reglamento";

function props(override: Partial<AwardDetailViewProps> = {}): AwardDetailViewProps {
  return {
    state: { kind: "ready", award: award({ status: "pending" }), academicYearName: "Año lectivo 2027" },
    backHref: "/scholarships/awards",
    onBack: vi.fn(),
    onViewStudentAwards: vi.fn(),
    ...override,
  };
}

describe("AwardDetailView", () => {
  it("AW57: a pending award says it changed nothing yet and offers the actions it is given, each reporting its click", async () => {
    const user = userEvent.setup();
    const onApprove = vi.fn();
    const onReject = vi.fn();
    renderWithAdminI18n(<AwardDetailView {...props({ onApprove, onReject })} />);

    expect(screen.getByRole("heading", { level: 2, name: "Otorgamiento del estudiante EST-0001" })).toBeInTheDocument();
    expect(screen.getAllByText("Pendiente de aprobación").length).toBeGreaterThan(0);
    expect(screen.getByText(/todavía no cambia ningún cargo/)).toBeInTheDocument();
    expect(screen.getByText("Año lectivo 2027")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Aprobar" }));
    await user.click(screen.getByRole("button", { name: "Rechazar" }));

    expect(onApprove).toHaveBeenCalled();
    expect(onReject).toHaveBeenCalled();
    expect(screen.queryByRole("button", { name: "Revocar" })).not.toBeInTheDocument();
  });

  it("AW57: draws no decision when no action is given, and always leads to the awards of the student", async () => {
    const user = userEvent.setup();
    const onViewStudentAwards = vi.fn();
    renderWithAdminI18n(<AwardDetailView {...props({ onViewStudentAwards })} />);

    expect(screen.queryByRole("button", { name: "Aprobar" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Ver otorgamientos del estudiante" }));
    expect(onViewStudentAwards).toHaveBeenCalledWith("00000000-0000-4000-8000-0000000000b1");
  });

  it("AW57: an approved award shows its decision and offers to revoke it", async () => {
    const user = userEvent.setup();
    const onRevoke = vi.fn();
    renderWithAdminI18n(<AwardDetailView {...props({ onRevoke, state: { kind: "ready", award: award({ status: "approved", decision: DECISION }), academicYearName: undefined } })} />);

    const decision = screen.getByRole("heading", { level: 3, name: "Decisión" }).nextElementSibling as HTMLElement;
    expect(within(decision).getByText("Aprobado")).toBeInTheDocument();
    expect(screen.getAllByText("—").length).toBeGreaterThan(0);
    await user.click(screen.getByRole("button", { name: "Revocar" }));
    expect(onRevoke).toHaveBeenCalled();
  });

  it("AW57: a revoked award shows the reason of the revocation and says that the charges already issued do not change", () => {
    const revocation = { ...DECISION, id: "00000000-0000-4000-8000-0000000d0002", kind: "revoked", reason: REASON } as const;
    renderWithAdminI18n(<AwardDetailView {...props({ state: { kind: "ready", award: award({ status: "revoked", decision: DECISION, revocation }), academicYearName: "Año lectivo 2027" } })} />);

    expect(screen.getByText(REASON)).toBeInTheDocument();
    expect(screen.getByText(/los ya emitidos no cambian/)).toBeInTheDocument();
  });

  it("AW57: shows the skeleton with its status text while it loads, and no action", () => {
    renderWithAdminI18n(<AwardDetailView {...props({ state: { kind: "loading" } })} />);

    expect(screen.getByRole("status")).toHaveTextContent("Cargando el otorgamiento.");
    expect(screen.queryByRole("button", { name: "Aprobar" })).not.toBeInTheDocument();
  });

  it("AW57: shows the error state with its retry, and a blocked state in words with the way back", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    const onBack = vi.fn();
    const { rerender } = renderWithAdminI18n(<AwardDetailView {...props({ state: { kind: "error", title: "No se pudo cargar el otorgamiento", description: "Ocurrió un error inesperado.", onRetry } })} />);
    await user.click(screen.getByRole("button", { name: "Reintentar" }));
    expect(onRetry).toHaveBeenCalled();

    rerender(<AwardDetailView {...props({ onBack, state: { kind: "blocked", title: "No se encontró el otorgamiento", description: "No existe." } })} />);
    expect(screen.getByText("No se encontró el otorgamiento")).toBeInTheDocument();
    await user.click(screen.getByRole("link", { name: "Volver a los otorgamientos" }));
    expect(onBack).toHaveBeenCalled();
  });

  it("AW57: tells the result of the last action in its region, with the trace of a failure", () => {
    renderWithAdminI18n(<AwardDetailView {...props({ notice: { tone: "error", message: "No se pudo.", traceId: "01J8Z4K2QF9P0X" } })} />);

    expect(screen.getByText("No se pudo.")).toBeInTheDocument();
    expect(screen.getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeInTheDocument();
  });
});
