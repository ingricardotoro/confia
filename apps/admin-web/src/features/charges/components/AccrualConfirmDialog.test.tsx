import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { accrualPreview } from "../../../test/contract-responses.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { AccrualConfirmDialog, type AccrualConfirmDialogProps } from "./AccrualConfirmDialog.tsx";

// States. AW57, AW58: the confirmation dialog rendered with props alone. Loading: `busy`, the confirm button waits and nothing
// closes the dialog. Error: `failure`, an alert inside the dialog (with the retry note when the answer may not have arrived).
// Success: the dialog with what will be posted. Empty does not apply: the dialog is only opened for a preview that has
// charges to create, so it never lists anything.
function props(override: Partial<AccrualConfirmDialogProps> = {}): AccrualConfirmDialogProps {
  return {
    planName: "Primaria 2027",
    periodLabel: "2027-02",
    preview: accrualPreview(),
    busy: false,
    onConfirm: vi.fn(),
    onCancel: vi.fn(),
    ...override,
  };
}

describe("AccrualConfirmDialog", () => {
  it("says what will be posted to the ledger, with the total as the server sent it, and that it cannot be undone from here", () => {
    renderWithAdminI18n(<AccrualConfirmDialog {...props()} />);

    const dialog = within(screen.getByRole("dialog", { name: "¿Generar los cargos de Primaria 2027?" }));
    expect(dialog.getByText(/Período 2027-02\. Se asentarán 2 cargos en el libro mayor\./)).toBeInTheDocument();
    expect(dialog.getByText("3,450.00 lempiras hondureños")).toBeInTheDocument();
    expect(dialog.getByText(/no se pueden deshacer desde esta pantalla/)).toBeInTheDocument();
    expect(dialog.getByText(/1 renglón con error no se generará/)).toBeInTheDocument();
  });

  it("sends nothing until the confirm button is pressed, and then asks once", async () => {
    const user = userEvent.setup();
    const onConfirm = vi.fn();
    renderWithAdminI18n(<AccrualConfirmDialog {...props({ onConfirm })} />);
    expect(onConfirm).not.toHaveBeenCalled();

    await user.click(screen.getByRole("button", { name: "Generar cargos" }));

    expect(onConfirm).toHaveBeenCalledOnce();
  });

  it("cancels with the button and with Escape", async () => {
    const user = userEvent.setup();
    const onCancel = vi.fn();
    renderWithAdminI18n(<AccrualConfirmDialog {...props({ onCancel })} />);

    await user.click(screen.getByRole("button", { name: "Cancelar" }));
    await user.keyboard("{Escape}");

    expect(onCancel).toHaveBeenCalledTimes(2);
  });

  it("waits while the request is in flight: neither the cancel button nor Escape closes it, and the confirm button cannot be pressed again", async () => {
    const user = userEvent.setup();
    const onCancel = vi.fn();
    renderWithAdminI18n(<AccrualConfirmDialog {...props({ onCancel, busy: true })} />);

    await user.keyboard("{Escape}");

    expect(onCancel).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "Cancelar" })).toBeDisabled();
    expect(screen.getByRole("button", { name: /Generar cargos/ })).toHaveAttribute("aria-busy", "true");
  });

  it("announces a refusal inside the dialog, with the trace id, and offers the same try again when the answer may not have arrived", () => {
    renderWithAdminI18n(<AccrualConfirmDialog {...props({ failure: { message: "Sin conexión.", traceId: "01J8Z4K2QF9P0X", retryable: true } })} />);

    const alert = within(screen.getByRole("alert"));
    expect(alert.getByText("Sin conexión.")).toBeInTheDocument();
    expect(alert.getByText(/Identificador para soporte: 01J8Z4K2QF9P0X/)).toBeInTheDocument();
    expect(alert.getByText(/no se duplicarán los cargos/)).toBeInTheDocument();
  });

  it("does not suggest trying again after a definite refusal", () => {
    renderWithAdminI18n(<AccrualConfirmDialog {...props({ failure: { message: "No tiene permiso para realizar esta acción.", retryable: false } })} />);

    expect(screen.getByRole("alert")).toHaveTextContent("No tiene permiso para realizar esta acción.");
    expect(screen.queryByText(/no se duplicarán los cargos/)).not.toBeInTheDocument();
  });
});
