import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { assignment } from "../../../test/contract-responses.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { AssignmentDetailView, type AssignmentDetailViewProps } from "./AssignmentDetailView.tsx";

// States. AW57, AW58: The detail of an assignment rendered with props alone. Loading: the skeleton with its status text. Error: the error state with its retry.
// Blocked: a 404 or a 403 told in words. Success: a pending assignment (with the decision actions it is given), an approved one, and a rejected or revoked
// one with the reason of the decision.
const DECISION = { id: "00000000-0000-4000-8000-0000000d0001", kind: "approved", decidedAt: "2026-11-20T15:00:00Z", decidedBy: "00000000-0000-4000-8000-0000000000a2" } as const;
const REASON = "El estudiante ya no cumple las condiciones del convenio";

function props(override: Partial<AssignmentDetailViewProps> = {}): AssignmentDetailViewProps {
  return {
    state: { kind: "ready", assignment: assignment({ status: "pending" }) },
    backHref: "/scholarships/discount-assignments",
    onBack: vi.fn(),
    onViewStudentAssignments: vi.fn(),
    ...override,
  };
}

describe("AssignmentDetailView", () => {
  it("a pending assignment says it changed nothing yet and offers the actions it is given, each reporting its click", async () => {
    const user = userEvent.setup();
    const onApprove = vi.fn();
    const onReject = vi.fn();
    renderWithAdminI18n(<AssignmentDetailView {...props({ onApprove, onReject })} />);

    expect(screen.getByRole("heading", { level: 2, name: "Asignación del estudiante EST-0001" })).toBeInTheDocument();
    expect(screen.getAllByText("Pendiente de aprobación").length).toBeGreaterThan(0);
    expect(screen.getByText(/todavía no cambia ningún cargo/)).toBeInTheDocument();
    expect(screen.getByText("CONV20 · Convenio con la empresa")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Aprobar" }));
    await user.click(screen.getByRole("button", { name: "Rechazar" }));

    expect(onApprove).toHaveBeenCalled();
    expect(onReject).toHaveBeenCalled();
    expect(screen.queryByRole("button", { name: "Revocar" })).not.toBeInTheDocument();
  });

  it("draws no decision when no action is given, and always leads to the assignments of the student", async () => {
    const user = userEvent.setup();
    const onViewStudentAssignments = vi.fn();
    renderWithAdminI18n(<AssignmentDetailView {...props({ onViewStudentAssignments })} />);

    expect(screen.queryByRole("button", { name: "Aprobar" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Ver asignaciones del estudiante" }));
    expect(onViewStudentAssignments).toHaveBeenCalledWith("00000000-0000-4000-8000-0000000000b1");
  });

  it("an approved assignment shows its decision and offers to revoke it", async () => {
    const user = userEvent.setup();
    const onRevoke = vi.fn();
    renderWithAdminI18n(<AssignmentDetailView {...props({ onRevoke, state: { kind: "ready", assignment: assignment({ status: "approved", decision: DECISION }) } })} />);

    const decision = screen.getByRole("heading", { level: 3, name: "Decisión" }).nextElementSibling as HTMLElement;
    expect(within(decision).getByText("Aprobado")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Revocar" }));
    expect(onRevoke).toHaveBeenCalled();
  });

  it("shows a dash where the assignment has no student code or no discount", () => {
    renderWithAdminI18n(<AssignmentDetailView {...props({ state: { kind: "ready", assignment: assignment({ studentCode: null, discountCode: null, discountName: null }) } })} />);

    expect(screen.getAllByText("—").length).toBeGreaterThan(0);
  });

  it("a revoked assignment shows the reason of the revocation and says that the charges already issued do not change", () => {
    const revocation = { ...DECISION, id: "00000000-0000-4000-8000-0000000d0002", kind: "revoked", reason: REASON } as const;
    renderWithAdminI18n(<AssignmentDetailView {...props({ state: { kind: "ready", assignment: assignment({ status: "revoked", decision: DECISION, revocation }) } })} />);

    expect(screen.getByText(REASON)).toBeInTheDocument();
    expect(screen.getByText(/los ya emitidos no cambian/)).toBeInTheDocument();
  });

  it("shows the skeleton with its status text while it loads, and no action", () => {
    renderWithAdminI18n(<AssignmentDetailView {...props({ state: { kind: "loading" } })} />);

    expect(screen.getByRole("status")).toHaveTextContent("Cargando la asignación.");
    expect(screen.queryByRole("button", { name: "Aprobar" })).not.toBeInTheDocument();
  });

  it("shows the error state with its retry, and a blocked state in words with the way back", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    const onBack = vi.fn();
    const { rerender } = renderWithAdminI18n(
      <AssignmentDetailView {...props({ state: { kind: "error", title: "No se pudo cargar la asignación", description: "Ocurrió un error inesperado.", onRetry } })} />,
    );
    await user.click(screen.getByRole("button", { name: "Reintentar" }));
    expect(onRetry).toHaveBeenCalled();

    rerender(<AssignmentDetailView {...props({ onBack, state: { kind: "blocked", title: "No se encontró la asignación", description: "No existe." } })} />);
    expect(screen.getByText("No se encontró la asignación")).toBeInTheDocument();
    await user.click(screen.getByRole("link", { name: "Volver a las asignaciones" }));
    expect(onBack).toHaveBeenCalled();
  });

  it("tells the result of the last action in its region, with the trace of a failure", () => {
    renderWithAdminI18n(<AssignmentDetailView {...props({ notice: { tone: "error", message: "No se pudo.", traceId: "01J8Z4K2QF9P0X" } })} />);

    expect(screen.getByText("No se pudo.")).toBeInTheDocument();
    expect(screen.getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeInTheDocument();
  });
});
