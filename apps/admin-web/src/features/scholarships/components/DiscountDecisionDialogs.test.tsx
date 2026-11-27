import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { discount, discountProposal } from "../../../test/contract-responses.ts";
import { formView } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { ApproveDiscountDialog, RejectDiscountDialog, type RejectDiscountDialogProps } from "./DiscountDecisionDialogs.tsx";

// States. AW57, AW58: The two decision dialogs of a campaign discount rendered with props alone. Loading: `form.submitting`, drawn by the dialog. Error: the error of the
// reason and the alert of the failure the form holds. Empty does not apply: a dialog lists no data. Success: each restates what it decides, so the
// decision is never blind: the terms of a discount waiting for its creation, or the terms in force beside the terms proposed.
const PENDING = discount({ code: "CAMP10", name: "Campaña de matrícula", approvalStatus: "pending", percent: "10.00" });
const WITH_PROPOSAL = discount({ code: "CAMP10", name: "Campaña de matrícula", percent: "10.00", pendingChange: discountProposal({ percent: "15.00", reactivate: true }) });

function rejectProps(override: Partial<RejectDiscountDialogProps> = {}): RejectDiscountDialogProps {
  return { discount: PENDING, reason: "", form: formView(), onReasonChange: vi.fn(), onReasonBlur: vi.fn(), onClose: vi.fn(), ...override };
}

describe("ApproveDiscountDialog", () => {
  it("restates the terms of a discount waiting for its creation, and says that the charges already issued do not change", () => {
    renderWithAdminI18n(<ApproveDiscountDialog discount={PENDING} form={formView()} onClose={vi.fn()} />);

    const dialog = screen.getByRole("dialog", { name: "Aprobar el descuento" });
    expect(within(dialog).getByRole("heading", { name: /Términos del descuento · CAMP10/ })).toBeInTheDocument();
    expect(within(dialog).getByText("10.00 %")).toBeInTheDocument();
    expect(within(dialog).queryByRole("heading", { name: "Términos propuestos" })).not.toBeInTheDocument();
    expect(within(dialog).getByText(/Los cargos ya emitidos no cambian/)).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Aprobar" })).toBeInTheDocument();
  });

  it("shows the terms in force beside the terms proposed when there is a proposal", () => {
    renderWithAdminI18n(<ApproveDiscountDialog discount={WITH_PROPOSAL} form={formView()} onClose={vi.fn()} />);

    const dialog = screen.getByRole("dialog", { name: "Aprobar la propuesta" });
    const current = within(within(dialog).getByRole("region", { name: /Términos vigentes · CAMP10/ }));
    const proposed = within(within(dialog).getByRole("region", { name: "Términos propuestos" }));
    expect(current.getByText("10.00 %")).toBeInTheDocument();
    expect(proposed.getByText("15.00 %")).toBeInTheDocument();
    expect(proposed.getByText("Reactivar el descuento")).toBeInTheDocument();
  });

  it("names the failure the form holds, and keeps the confirm button busy while it sends", () => {
    renderWithAdminI18n(
      <ApproveDiscountDialog
        discount={PENDING}
        form={formView({ submitting: true, alert: { message: "Quien creó el descuento o propuso el cambio no puede aprobarlo ni rechazarlo.", traceId: "01J8Z4K2QF9P0X" } })}
        onClose={vi.fn()}
      />,
    );

    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText(/Quien creó el descuento o propuso el cambio no puede aprobarlo/)).toBeInTheDocument();
    expect(within(dialog).getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: /Aprobar/ })).toHaveAttribute("aria-busy", "true");
  });
});

describe("RejectDiscountDialog", () => {
  it("restates the discount and asks for the reason of 20 to 500 characters, which reports what is typed", async () => {
    const user = userEvent.setup();
    const onReasonChange = vi.fn();
    renderWithAdminI18n(<RejectDiscountDialog {...rejectProps({ onReasonChange })} />);

    const dialog = screen.getByRole("dialog", { name: "Rechazar el descuento" });
    expect(within(dialog).getByText("10.00 %")).toBeInTheDocument();
    const reason = within(dialog).getByRole("textbox", { name: /Motivo del rechazo/ });
    expect(reason).toBeRequired();
    await user.type(reason, "M");
    expect(onReasonChange).toHaveBeenCalledWith("M");
  });

  it("titles the rejection of a proposal as such and shows the proposed terms", () => {
    renderWithAdminI18n(<RejectDiscountDialog {...rejectProps({ discount: WITH_PROPOSAL })} />);

    const dialog = screen.getByRole("dialog", { name: "Rechazar la propuesta" });
    expect(within(dialog).getByText("15.00 %")).toBeInTheDocument();
    expect(within(dialog).getByText(/Los términos vigentes siguen aplicándose/)).toBeInTheDocument();
  });

  it("shows the error of the reason in words", () => {
    renderWithAdminI18n(<RejectDiscountDialog {...rejectProps({ reasonError: "Escriba de 20 a 500 caracteres." })} />);

    expect(screen.getByRole("textbox", { name: /Motivo del rechazo/ })).toBeInvalid();
    expect(screen.getByText("Escriba de 20 a 500 caracteres.")).toBeInTheDocument();
  });
});
