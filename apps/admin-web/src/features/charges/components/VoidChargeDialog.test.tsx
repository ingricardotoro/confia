import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { VoidChargeDialog, type VoidChargeDialogProps } from "./VoidChargeDialog.tsx";

// States. AW57, AW58: the void dialog rendered with props alone. Loading: `busy`, the confirm button waits, the reason is read only and nothing closes
// the dialog. Error: `failure` (an alert inside the dialog, with the retry note when the answer may not have arrived) and `problem` (the
// reason does not hold, told on the field). Success: the dialog with what will be posted. Empty does not apply: the dialog is only opened
// for a charge.
function props(override: Partial<VoidChargeDialogProps> = {}): VoidChargeDialogProps {
  return {
    charge: { conceptName: "Mensualidad", conceptCode: "MENS", periodLabel: "2027-02", total: { amount: "1150.00", currency: "HNL" } },
    reason: "",
    onReasonChange: vi.fn(),
    busy: false,
    onReasonBlur: vi.fn(),
    onConfirm: vi.fn(),
    onCancel: vi.fn(),
    ...override,
  };
}

describe("VoidChargeDialog", () => {
  it("restates the charge with its total as the server sent it, says it cannot be undone, and warns not to write personal data", () => {
    renderWithAdminI18n(<VoidChargeDialog {...props()} />);

    const dialog = within(screen.getByRole("dialog", { name: "Anular cargo" }));
    expect(dialog.getByText("Anular el cargo Mensualidad del período 2027-02.")).toBeInTheDocument();
    expect(dialog.getByText("1,150.00 lempiras hondureños")).toBeInTheDocument();
    expect(dialog.getByText(/La anulación no se puede deshacer\. El libro mayor recibe un reverso exacto/)).toBeInTheDocument();
    expect(dialog.getByRole("textbox", { name: /Motivo de la anulación/ })).toHaveAccessibleDescription(/No escriba nombres, números de documento ni datos de salud o familiares/);
  });

  it("shows the live count of the reason", () => {
    renderWithAdminI18n(<VoidChargeDialog {...props({ reason: "  Cargo duplicado  " })} />);

    expect(screen.getByText("15 de 500 caracteres")).toBeInTheDocument();
  });

  it("sends the typed reason up and asks to confirm once", async () => {
    const user = userEvent.setup();
    const onReasonChange = vi.fn();
    const onConfirm = vi.fn();
    renderWithAdminI18n(<VoidChargeDialog {...props({ onReasonChange, onConfirm })} />);

    await user.type(screen.getByRole("textbox", { name: /Motivo de la anulación/ }), "a");
    await user.click(screen.getByRole("button", { name: "Anular cargo" }));

    expect(onReasonChange).toHaveBeenCalledWith("a");
    expect(onConfirm).toHaveBeenCalledOnce();
  });

  it.each([
    ["tooShort", "El motivo debe tener al menos 20 caracteres."],
    ["tooLong", "El motivo no puede tener más de 500 caracteres."],
    ["control", "El motivo no puede tener saltos de línea ni caracteres de control."],
  ] as const)("tells the %s problem on the field, in text", (problem, text) => {
    renderWithAdminI18n(<VoidChargeDialog {...props({ problem })} />);

    const field = screen.getByRole("textbox", { name: /Motivo de la anulación/ });
    expect(field).toBeInvalid();
    expect(field).toHaveAccessibleDescription(new RegExp(text.slice(0, 20)));
  });

  it("cancels with the button and with Escape", async () => {
    const user = userEvent.setup();
    const onCancel = vi.fn();
    renderWithAdminI18n(<VoidChargeDialog {...props({ onCancel })} />);

    await user.click(screen.getByRole("button", { name: "Cancelar" }));
    await user.keyboard("{Escape}");

    expect(onCancel).toHaveBeenCalledTimes(2);
  });

  it("waits while the request is in flight: nothing closes it, the reason is read only and the confirm button reports it is busy", async () => {
    const user = userEvent.setup();
    const onCancel = vi.fn();
    renderWithAdminI18n(<VoidChargeDialog {...props({ onCancel, busy: true })} />);

    await user.keyboard("{Escape}");

    expect(onCancel).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "Cancelar" })).toBeDisabled();
    expect(screen.getByRole("textbox", { name: /Motivo de la anulación/ })).toHaveAttribute("readonly");
    expect(screen.getByRole("button", { name: /Anular cargo/ })).toHaveAttribute("aria-busy", "true");
  });

  it("announces a refusal inside the dialog, with the trace id, and offers the same try again when the answer may not have arrived", () => {
    renderWithAdminI18n(<VoidChargeDialog {...props({ failure: { message: "Sin conexión.", traceId: "01J8Z4K2QF9P0X", retryable: true } })} />);

    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("Sin conexión.");
    expect(alert).toHaveTextContent("Identificador para soporte: 01J8Z4K2QF9P0X");
    expect(alert).toHaveTextContent("el cargo no se anulará dos veces");
  });

  it("does not promise a retry for a refusal the server will repeat", () => {
    renderWithAdminI18n(<VoidChargeDialog {...props({ failure: { message: "No tiene permiso.", retryable: false } })} />);

    expect(screen.getByRole("alert")).not.toHaveTextContent("no se anulará dos veces");
  });
});
