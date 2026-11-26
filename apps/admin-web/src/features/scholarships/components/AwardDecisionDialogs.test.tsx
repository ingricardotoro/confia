import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { award } from "../../../test/contract-responses.ts";
import { formView } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { ApproveAwardDialog, RejectAwardDialog, RevokeAwardDialog, type ReasonDialogProps } from "./AwardDecisionDialogs.tsx";

// States. AW57, AW58: the three decision dialogs of an award rendered with props alone. Loading: `form.submitting`, drawn by the dialog. Error: the
// error of the reason and the alert of the failure the form holds. Empty does not apply: a dialog lists no data. Success: each restates the award it
// decides, so the decision is never blind, and says what it does.
const AWARD = award({ studentCode: "EST-0001", scholarshipCode: "BECA25", scholarshipName: "Beca de excelencia", validFrom: "2027-01-01", validTo: "2027-12-31" });

function reasonProps(override: Partial<ReasonDialogProps> = {}): ReasonDialogProps {
  return { award: AWARD, reason: "", form: formView(), onReasonChange: vi.fn(), onReasonBlur: vi.fn(), onClose: vi.fn(), ...override };
}

describe("ApproveAwardDialog", () => {
  it("AW57: restates the student, the scholarship and the validity, and says that the charges already issued do not change", () => {
    renderWithAdminI18n(<ApproveAwardDialog award={AWARD} form={formView()} onClose={vi.fn()} />);

    const dialog = screen.getByRole("dialog", { name: "Aprobar el otorgamiento" });
    expect(within(dialog).getByText("EST-0001")).toBeInTheDocument();
    expect(within(dialog).getByText("BECA25 · Beca de excelencia")).toBeInTheDocument();
    expect(within(dialog).getByText("Del 1 ene 2027 al 31 dic 2027")).toBeInTheDocument();
    expect(within(dialog).getByText(/Los cargos ya emitidos no cambian/)).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Aprobar otorgamiento" })).toBeInTheDocument();
  });

  it("AW57: names the failure the form holds, and keeps the confirm button busy while it sends", () => {
    renderWithAdminI18n(
      <ApproveAwardDialog award={AWARD} form={formView({ submitting: true, alert: { message: "Quien solicitó el otorgamiento no puede aprobarlo ni rechazarlo.", traceId: "01J8Z4K2QF9P0X" } })} onClose={vi.fn()} />,
    );

    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText("Quien solicitó el otorgamiento no puede aprobarlo ni rechazarlo.")).toBeInTheDocument();
    expect(within(dialog).getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: /Aprobar otorgamiento/ })).toHaveAttribute("aria-busy", "true");
  });
});

describe("RejectAwardDialog and RevokeAwardDialog", () => {
  it("AW57: the rejection asks for a reason of 20 to 500 characters and reports what is typed and the end of the edit", async () => {
    const user = userEvent.setup();
    const onReasonChange = vi.fn();
    const onReasonBlur = vi.fn();
    renderWithAdminI18n(<RejectAwardDialog {...reasonProps({ onReasonChange, onReasonBlur })} />);

    const dialog = screen.getByRole("dialog", { name: "Rechazar el otorgamiento" });
    const reason = within(dialog).getByRole("textbox", { name: /Motivo del rechazo/ });
    expect(reason).toBeRequired();
    expect(reason).toHaveAttribute("maxlength", "500");
    expect(within(dialog).getByText("De 20 a 500 caracteres. No escriba datos personales.")).toBeInTheDocument();
    await user.type(reason, "x");
    await user.tab();

    expect(onReasonChange).toHaveBeenCalledWith("x");
    expect(onReasonBlur).toHaveBeenCalled();
  });

  it("AW57: the revocation says that only the charges to come are affected, shows the error of the reason, and restates the award", () => {
    renderWithAdminI18n(<RevokeAwardDialog {...reasonProps({ reasonError: "Escriba de 20 a 500 caracteres." })} />);

    const dialog = screen.getByRole("dialog", { name: "Revocar el otorgamiento" });
    expect(within(dialog).getByText(/solo afecta los cargos que se generen desde ahora/)).toBeInTheDocument();
    expect(within(dialog).getByText("Escriba de 20 a 500 caracteres.")).toBeInTheDocument();
    expect(within(dialog).getByRole("textbox", { name: /Motivo de la revocación/ })).toBeInvalid();
    expect(within(dialog).getByText("EST-0001")).toBeInTheDocument();
  });

  it("AW57: shows a dash where the award has no student code or no scholarship name", () => {
    renderWithAdminI18n(<RevokeAwardDialog {...reasonProps({ award: award({ studentCode: null, scholarshipCode: null, scholarshipName: null }) })} />);

    expect(within(screen.getByRole("dialog")).getAllByText("—").length).toBe(2);
  });
});
