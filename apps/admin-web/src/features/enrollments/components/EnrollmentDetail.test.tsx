import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { enrollmentTransfer, studentEnrollment } from "../../../test/contract-responses.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { EnrollmentDetail, type EnrollmentDetailProps } from "./EnrollmentDetail.tsx";

// States. AW57, AW58: the detail rendered with props alone. Loading: a skeleton with a text for assistive technology. Error:
// the message with a retry. Empty: an enrollment with no transfers says so. Success: the facts and the history, read only.
function props(override: Partial<EnrollmentDetailProps> = {}): EnrollmentDetailProps {
  return { state: { kind: "ready", row: studentEnrollment({ transfers: [] }) }, onClose: vi.fn(), ...override };
}

describe("EnrollmentDetail", () => {
  it("AW57: shows the facts and the history of transfers, oldest first, as a table", () => {
    const row = studentEnrollment({
      transfers: [
        enrollmentTransfer({ fromSectionName: "A", toSectionName: "B", transferredOn: "2026-03-02", reason: "Cambio de turno" }),
        enrollmentTransfer({ fromSectionName: "B", toSectionName: "C", transferredOn: "2026-04-02", reason: null }),
      ],
    });
    renderWithAdminI18n(<EnrollmentDetail {...props({ state: { kind: "ready", row } })} />);

    const dialog = screen.getByRole("dialog", { name: "Detalle de la matrícula" });
    expect(within(dialog).getByText("Ana Pérez · E-000001")).toBeInTheDocument();
    expect(within(dialog).getByText("Primaria")).toBeInTheDocument();
    const history = within(dialog).getByRole("table", { name: /Traslados de sección/ });
    expect(within(history).getAllByRole("row")).toHaveLength(3);
    expect(within(history).getByText("Cambio de turno")).toBeInTheDocument();
    expect(within(history).getByText("Sin motivo")).toBeInTheDocument();
    expect(within(dialog).queryByRole("textbox")).not.toBeInTheDocument();
  });

  it("AW58: says an enrollment never moved, and shows the day and the reason of an exit", () => {
    const row = studentEnrollment({ status: "WITHDRAWN", withdrawnOn: "2026-05-04", withdrawalReason: "Cambio de ciudad" });
    renderWithAdminI18n(<EnrollmentDetail {...props({ state: { kind: "ready", row } })} />);

    expect(screen.getByText("Esta matrícula no tiene traslados de sección.")).toBeInTheDocument();
    expect(screen.getByText("Cambio de ciudad")).toBeInTheDocument();
    expect(screen.getByText("Retirada")).toBeInTheDocument();
  });

  it("AW58: loads as a skeleton that is announced as busy, and fails with a retry", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    const { rerender } = renderWithAdminI18n(<EnrollmentDetail {...props({ state: { kind: "loading" } })} />);
    expect(screen.getByText("Cargando el detalle de la matrícula.")).toBeInTheDocument();

    rerender(<EnrollmentDetail {...props({ state: { kind: "failed", description: "Sin conexión.", onRetry } })} />);
    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(screen.getByText("No se pudo cargar el detalle de la matrícula")).toBeInTheDocument();
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it("AW57: closes with the button of the footer", async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    renderWithAdminI18n(<EnrollmentDetail {...props({ onClose })} />);

    await user.click(screen.getAllByRole("button", { name: "Cerrar" })[0] as HTMLElement);

    expect(onClose).toHaveBeenCalled();
  });
});
