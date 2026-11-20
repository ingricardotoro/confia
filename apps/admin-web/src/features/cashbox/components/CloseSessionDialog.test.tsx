import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { formView } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { CloseSessionDialog, type CloseSessionDialogProps } from "./CloseSessionDialog.tsx";

// States. AW57, AW58: the close of a session rendered with props alone. Loading: while the request is in flight the submit button waits. Empty: nothing typed.
// Error: the error of the counted cash, the one of the reason of the difference, and the alert of a refusal of the server. Success: the expected cash beside
// the field of the count, and no difference anywhere, because the browser computes none.
function props(override: Partial<CloseSessionDialogProps> = {}): CloseSessionDialogProps {
  return {
    expected: { amount: "550.00", currency: "HNL" },
    values: { declaredAmount: "", differenceReason: "" },
    fieldErrors: {},
    form: formView(),
    onChange: vi.fn(),
    onBlur: vi.fn(),
    onClose: vi.fn(),
    ...override,
  };
}

describe("CloseSessionDialog", () => {
  it("AW57: warns that the close cannot be undone and shows the expected cash beside the field of the count, with no difference", () => {
    renderWithAdminI18n(<CloseSessionDialog {...props()} />);

    expect(screen.getByRole("dialog", { name: "Cerrar caja" })).toBeInTheDocument();
    expect(screen.getByText(/El cierre no se puede deshacer/)).toBeInTheDocument();
    expect(screen.getAllByText(/550\.00/).length).toBeGreaterThan(0);
    expect(screen.getByRole("textbox", { name: /Efectivo contado/ })).toBeRequired();
    expect(screen.queryByText(/^Diferencia/)).not.toBeInTheDocument();
  });

  it("offers the reason of a difference as an optional field with its bounds", () => {
    renderWithAdminI18n(<CloseSessionDialog {...props()} />);

    const reason = screen.getByRole("textbox", { name: /Motivo de la diferencia/ });
    expect(reason).not.toBeRequired();
    expect(reason).toHaveAccessibleDescription(/de 20 a 500 caracteres/);
  });

  it("reports what is typed in each field", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithAdminI18n(<CloseSessionDialog {...props({ onChange })} />);

    await user.type(screen.getByRole("textbox", { name: /Efectivo contado/ }), "5");
    await user.type(screen.getByRole("textbox", { name: /Motivo de la diferencia/ }), "x");

    expect(onChange).toHaveBeenCalledWith("declaredAmount", "5");
    expect(onChange).toHaveBeenCalledWith("differenceReason", "x");
  });

  it("describes the error of the reason under its field, as the server asked for it", () => {
    renderWithAdminI18n(<CloseSessionDialog {...props({ fieldErrors: { differenceReason: "Lo contado es distinto de lo esperado: escriba el motivo." } })} />);

    const reason = screen.getByRole("textbox", { name: /Motivo de la diferencia/ });
    expect(reason).toBeInvalid();
    expect(reason).toHaveAccessibleDescription(/escriba el motivo/);
  });

  it("waits on the submit button while the request is in flight", () => {
    renderWithAdminI18n(<CloseSessionDialog {...props({ form: formView({ submitting: true }) })} />);

    expect(screen.getByRole("button", { name: /Cerrar caja/ })).toHaveAttribute("aria-busy", "true");
  });
});
