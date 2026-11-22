import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { paymentBody } from "../../../test/contract-responses.ts";
import { formView } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { ConfirmPaymentDialog, RejectPaymentDialog } from "./DecisionDialogs.tsx";

// States. AW57, AW58: the two decision dialogs of a declared transfer rendered with props alone. Loading: the submit button waits while the request is in
// flight. Error: the alert of a refusal and the summary of the fields come in through the form. Empty does not apply: the dialog restates a payment.
// Success: each dialog restates what is being decided, as the server holds it, and the rejection asks for its reason.
const PAYMENT = paymentBody({ method: "BANK_TRANSFER", status: "declared", reference: "AB1234", bankName: "Banco Atlántida", amount: { amount: "1265.00", currency: "HNL" } });

describe("ConfirmPaymentDialog", () => {
  it("AW57: restates the payment and what confirming does, and sends the form when confirmed", async () => {
    const user = userEvent.setup();
    const form = formView();
    renderWithAdminI18n(<ConfirmPaymentDialog payment={PAYMENT} form={form} onClose={vi.fn()} />);

    expect(screen.getByRole("dialog", { name: "Confirmar la transferencia" })).toBeInTheDocument();
    expect(screen.getByText("AB1234")).toBeInTheDocument();
    expect(screen.getAllByText(/1,265\.00/).length).toBeGreaterThan(0);
    expect(screen.getByText(/se asienta en el libro mayor/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Confirmar transferencia" }));
    expect(form.onSubmit).toHaveBeenCalled();
  });

  it("AW58: tells the refusal of the server in the dialog and waits while it sends", () => {
    renderWithAdminI18n(<ConfirmPaymentDialog payment={PAYMENT} form={formView({ alert: { message: "Quien registró el pago no puede confirmarlo.", traceId: "01J8" }, submitting: true })} onClose={vi.fn()} />);

    expect(screen.getByText("Quien registró el pago no puede confirmarlo.")).toBeInTheDocument();
    expect(screen.getByText(/Identificador para soporte: 01J8/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Confirmar transferencia/ })).toHaveAttribute("aria-busy", "true");
  });
});

describe("RejectPaymentDialog", () => {
  it("AW57: restates the payment, asks for the reason with its bounds and reports what is typed", async () => {
    const user = userEvent.setup();
    const onReasonChange = vi.fn();
    renderWithAdminI18n(<RejectPaymentDialog payment={PAYMENT} reason="" form={formView()} onReasonChange={onReasonChange} onReasonBlur={vi.fn()} onClose={vi.fn()} />);

    expect(screen.getByRole("dialog", { name: "Rechazar la transferencia" })).toBeInTheDocument();
    expect(screen.getByText("Banco Atlántida")).toBeInTheDocument();
    await user.type(screen.getByRole("textbox", { name: /Motivo del rechazo/ }), "x");
    expect(onReasonChange).toHaveBeenCalledWith("x");
    expect(screen.getByText(/De 20 a 500 caracteres/)).toBeInTheDocument();
  });

  it("AW58: shows the error of the reason under its field", () => {
    renderWithAdminI18n(<RejectPaymentDialog payment={PAYMENT} reason="corto" reasonError="Escriba de 20 a 500 caracteres." form={formView()} onReasonChange={vi.fn()} onReasonBlur={vi.fn()} onClose={vi.fn()} />);

    expect(screen.getByText("Escriba de 20 a 500 caracteres.")).toBeInTheDocument();
  });
});
