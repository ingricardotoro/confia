import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { formView } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { MovementDialog, type MovementDialogProps } from "./MovementDialog.tsx";

// States. AW57, AW58: the form of a movement rendered with props alone. Loading: while the request is in flight the submit button waits. Empty: nothing typed.
// Error: the field errors and the alert of a refusal of the server. Success: each of the three kinds of movement with its own title and its own note rule.
function props(override: Partial<MovementDialogProps> = {}): MovementDialogProps {
  return {
    type: "deposit",
    expected: { amount: "500.00", currency: "HNL" },
    values: { amount: "", notes: "" },
    fieldErrors: {},
    form: formView(),
    onChange: vi.fn(),
    onBlur: vi.fn(),
    onClose: vi.fn(),
    ...override,
  };
}

describe("MovementDialog", () => {
  it.each([
    ["deposit", "Registrar depósito"],
    ["withdrawal", "Registrar retiro"],
    ["adjustment", "Registrar ajuste"],
  ] as const)("AW57: a %s has its own title and shows the expected cash it acts on", (type, title) => {
    renderWithAdminI18n(<MovementDialog {...props({ type })} />);

    expect(screen.getByRole("dialog", { name: title })).toBeInTheDocument();
    expect(screen.getByText(/Efectivo esperado ahora/)).toBeInTheDocument();
    expect(screen.getAllByText(/500\.00/).length).toBeGreaterThan(0);
  });

  it("asks for the note of an adjustment and leaves it optional for the others", () => {
    const { rerender } = renderWithAdminI18n(<MovementDialog {...props({ type: "adjustment" })} />);
    expect(screen.getByRole("textbox", { name: /Notas/ })).toBeRequired();
    expect(screen.getByRole("textbox", { name: /Notas/ })).toHaveAttribute("maxlength", "500");

    rerender(<MovementDialog {...props({ type: "deposit" })} />);
    expect(screen.getByRole("textbox", { name: /Notas/ })).not.toBeRequired();
  });

  it("reports what is typed in each field and closes on cancel", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    const onClose = vi.fn();
    renderWithAdminI18n(<MovementDialog {...props({ onChange, onClose })} />);

    await user.type(screen.getByRole("textbox", { name: /Importe/ }), "1");
    await user.type(screen.getByRole("textbox", { name: /Notas/ }), "a");
    await user.click(screen.getByRole("button", { name: "Cancelar" }));

    expect(onChange).toHaveBeenCalledWith("amount", "1");
    expect(onChange).toHaveBeenCalledWith("notes", "a");
    expect(onClose).toHaveBeenCalledOnce();
  });

  it("describes the field errors and tells a refusal of the server in an alert", () => {
    renderWithAdminI18n(
      <MovementDialog
        {...props({
          fieldErrors: { amount: "El importe debe ser mayor que cero." },
          form: formView({ alert: { message: "El movimiento dejaría el efectivo esperado por debajo de cero." } }),
        })}
      />,
    );

    expect(screen.getByRole("textbox", { name: /Importe/ })).toBeInvalid();
    expect(screen.getByText("El movimiento dejaría el efectivo esperado por debajo de cero.")).toBeInTheDocument();
  });

  it("waits on the submit button while the request is in flight", () => {
    renderWithAdminI18n(<MovementDialog {...props({ form: formView({ submitting: true }) })} />);

    expect(screen.getByRole("button", { name: /Registrar depósito/ })).toHaveAttribute("aria-busy", "true");
    expect(screen.getByRole("button", { name: "Cancelar" })).toBeDisabled();
  });
});
