import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { assignment } from "../../../test/contract-responses.ts";
import { formView } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { ApproveAssignmentDialog, RejectAssignmentDialog, RevokeAssignmentDialog, type AssignmentReasonDialogProps } from "./AssignmentDecisionDialogs.tsx";

// States. AW57, AW58: The three decision dialogs of an assignment rendered with props alone. Loading: `form.submitting`, drawn by the dialog. Error: the error of the
// reason and the alert of the failure the form holds. Empty does not apply: a dialog lists no data. Success: each restates the assignment it decides, so
// the decision is never blind, and says what it does.
const ASSIGNMENT = assignment({ studentCode: "EST-0001", discountCode: "CONV20", discountName: "Convenio con la empresa", validFrom: "2027-01-01", validTo: "2027-12-31" });

function reasonProps(override: Partial<AssignmentReasonDialogProps> = {}): AssignmentReasonDialogProps {
  return { assignment: ASSIGNMENT, reason: "", form: formView(), onReasonChange: vi.fn(), onReasonBlur: vi.fn(), onClose: vi.fn(), ...override };
}

describe("ApproveAssignmentDialog", () => {
  it("restates the student, the discount and the validity, and says that the charges already issued do not change", () => {
    renderWithAdminI18n(<ApproveAssignmentDialog assignment={ASSIGNMENT} form={formView()} onClose={vi.fn()} />);

    const dialog = screen.getByRole("dialog", { name: "Aprobar la asignación" });
    expect(within(dialog).getByText("EST-0001")).toBeInTheDocument();
    expect(within(dialog).getByText("CONV20 · Convenio con la empresa")).toBeInTheDocument();
    expect(within(dialog).getByText("Del 1 ene 2027 al 31 dic 2027")).toBeInTheDocument();
    expect(within(dialog).getByText(/Los cargos ya emitidos no cambian/)).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Aprobar asignación" })).toBeInTheDocument();
  });

  it("names the failure the form holds, and keeps the confirm button busy while it sends", () => {
    renderWithAdminI18n(
      <ApproveAssignmentDialog
        assignment={ASSIGNMENT}
        form={formView({ submitting: true, alert: { message: "Quien solicitó la asignación no puede aprobarla ni rechazarla.", traceId: "01J8Z4K2QF9P0X" } })}
        onClose={vi.fn()}
      />,
    );

    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText("Quien solicitó la asignación no puede aprobarla ni rechazarla.")).toBeInTheDocument();
    expect(within(dialog).getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: /Aprobar asignación/ })).toHaveAttribute("aria-busy", "true");
  });
});

describe("RejectAssignmentDialog and RevokeAssignmentDialog", () => {
  it("the rejection asks for a reason of 20 to 500 characters and reports what is typed and the end of the edit", async () => {
    const user = userEvent.setup();
    const onReasonChange = vi.fn();
    const onReasonBlur = vi.fn();
    renderWithAdminI18n(<RejectAssignmentDialog {...reasonProps({ onReasonChange, onReasonBlur })} />);

    const dialog = screen.getByRole("dialog", { name: "Rechazar la asignación" });
    const reason = within(dialog).getByRole("textbox", { name: /Motivo del rechazo/ });
    expect(reason).toBeRequired();
    expect(reason).toHaveAttribute("maxlength", "500");
    expect(within(dialog).getByText("De 20 a 500 caracteres. No escriba datos personales.")).toBeInTheDocument();
    await user.type(reason, "x");
    await user.tab();

    expect(onReasonChange).toHaveBeenCalledWith("x");
    expect(onReasonBlur).toHaveBeenCalled();
  });

  it("the revocation says that only the charges to come are affected, shows the error of the reason, and restates the assignment", () => {
    renderWithAdminI18n(<RevokeAssignmentDialog {...reasonProps({ reasonError: "Escriba de 20 a 500 caracteres." })} />);

    const dialog = screen.getByRole("dialog", { name: "Revocar la asignación" });
    expect(within(dialog).getByText(/solo afecta los cargos que se generen desde ahora/)).toBeInTheDocument();
    expect(within(dialog).getByText("Escriba de 20 a 500 caracteres.")).toBeInTheDocument();
    expect(within(dialog).getByRole("textbox", { name: /Motivo de la revocación/ })).toBeInvalid();
    expect(within(dialog).getByText("EST-0001")).toBeInTheDocument();
  });

  it("shows a dash where the assignment has no student code or no discount name", () => {
    renderWithAdminI18n(<RevokeAssignmentDialog {...reasonProps({ assignment: assignment({ studentCode: null, discountCode: null, discountName: null }) })} />);

    expect(within(screen.getByRole("dialog")).getAllByText("—").length).toBe(2);
  });
});
