import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { formView } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { ApproveCloseDialog, type ApproveCloseDialogProps } from "./ApproveCloseDialog.tsx";

// States. AW57, AW58: the approval of a close rendered with props alone. Loading: while the request is in flight the submit button waits. Empty: no note yet.
// Error: the error of the note and the alert of a refusal of the server (the cashier cannot approve their own close). Success: the close restated with what
// the server computed.
function props(override: Partial<ApproveCloseDialogProps> = {}): ApproveCloseDialogProps {
  return {
    close: {
      id: "00000000-0000-4000-8000-0000000c0aa1",
      closedAt: "2026-11-19T18:00:00Z",
      closedBy: "00000000-0000-4000-8000-0000000000a1",
      declaredAmount: { amount: "540.00", currency: "HNL" },
      expectedAmount: { amount: "550.00", currency: "HNL" },
      differenceAmount: { amount: "-10.00", currency: "HNL" },
      differenceReason: "Billete de cien contado dos veces por error",
    },
    note: "",
    form: formView(),
    onNoteChange: vi.fn(),
    onNoteBlur: vi.fn(),
    onClose: vi.fn(),
    ...override,
  };
}

describe("ApproveCloseDialog", () => {
  it("AW57: restates the expected cash, the count, the difference and its reason before asking for the note", () => {
    renderWithAdminI18n(<ApproveCloseDialog {...props()} />);

    expect(screen.getByRole("dialog", { name: "Aprobar el cierre" })).toBeInTheDocument();
    expect(screen.getAllByText(/550\.00/).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/540\.00/).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/-.*10\.00/).length).toBeGreaterThan(0);
    expect(screen.getByText("Billete de cien contado dos veces por error")).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: /Nota de la aprobación/ })).toBeRequired();
  });

  it("reports what is typed and when the note is left", async () => {
    const user = userEvent.setup();
    const onNoteChange = vi.fn();
    const onNoteBlur = vi.fn();
    renderWithAdminI18n(<ApproveCloseDialog {...props({ onNoteChange, onNoteBlur })} />);

    await user.type(screen.getByRole("textbox", { name: /Nota de la aprobación/ }), "R");
    await user.tab();

    expect(onNoteChange).toHaveBeenCalledWith("R");
    expect(onNoteBlur).toHaveBeenCalled();
  });

  it("describes the error of the note and tells a refusal of the server in an alert", () => {
    renderWithAdminI18n(
      <ApproveCloseDialog {...props({ noteError: "Este dato es obligatorio.", form: formView({ alert: { message: "Quien cerró la caja no puede aprobar su propio cierre." } }) })} />,
    );

    expect(screen.getByRole("textbox", { name: /Nota de la aprobación/ })).toBeInvalid();
    expect(screen.getByText("Quien cerró la caja no puede aprobar su propio cierre.")).toBeInTheDocument();
  });

  it("waits on the submit button while the request is in flight", () => {
    renderWithAdminI18n(<ApproveCloseDialog {...props({ form: formView({ submitting: true }) })} />);

    expect(screen.getByRole("button", { name: /Aprobar cierre/ })).toHaveAttribute("aria-busy", "true");
  });
});
