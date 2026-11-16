import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { paymentConcept } from "../../../test/contract-responses.ts";
import { formView } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { initialPaymentConceptValues } from "../payment-concept-form.ts";
import { PaymentConceptForm, type PaymentConceptFormProps } from "./PaymentConceptForm.tsx";

// States. AW57, AW58: the form of a payment concept rendered with props alone. Loading: `form.submitting`, drawn by the dialog.
// Error: `fieldErrors` under each field. Empty does not apply: a form lists no data. Success: a create form and an
// edit form, which differ in the code (fixed once created) and in the active switch.
function props(override: Partial<PaymentConceptFormProps> = {}): PaymentConceptFormProps {
  return {
    mode: "create",
    values: initialPaymentConceptValues(),
    fieldErrors: {},
    form: formView(),
    onChange: vi.fn(),
    onBlur: vi.fn(),
    onClose: vi.fn(),
    ...override,
  };
}

describe("PaymentConceptForm", () => {
  it("a new concept has a code, a name and a category, all required, and no active switch", () => {
    renderWithAdminI18n(<PaymentConceptForm {...props()} />);

    const dialog = screen.getByRole("dialog", { name: "Nuevo concepto de pago" });
    expect(within(dialog).getByLabelText(/Código/)).toBeRequired();
    expect(within(dialog).getByLabelText(/Nombre/)).toBeRequired();
    expect(within(dialog).getByLabelText(/Categoría/)).toBeRequired();
    expect(within(dialog).getByRole("checkbox", { name: "Recurrente" })).not.toBeChecked();
    expect(within(dialog).getByRole("checkbox", { name: "Gravado" })).not.toBeChecked();
    expect(within(dialog).queryByRole("checkbox", { name: "Activo" })).not.toBeInTheDocument();
  });

  it("an existing concept shows its code as read only and has the active switch", () => {
    const row = paymentConcept({ code: "COL" });
    renderWithAdminI18n(<PaymentConceptForm {...props({ mode: "edit", values: initialPaymentConceptValues(row) })} />);

    const dialog = screen.getByRole("dialog", { name: "Editar concepto de pago" });
    expect(within(dialog).getByLabelText(/Código/)).toHaveValue("COL");
    expect(within(dialog).getByLabelText(/Código/)).toHaveAttribute("readonly");
    expect(within(dialog).getByRole("checkbox", { name: "Activo" })).toBeChecked();
    expect(within(dialog).getByRole("checkbox", { name: "Recurrente" })).toBeChecked();
  });

  it("reports each change with the name of the field and a text value, for the text, the select and the checkboxes", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithAdminI18n(<PaymentConceptForm {...props({ onChange })} />);

    await user.type(screen.getByLabelText(/Código/), "C");
    await user.selectOptions(screen.getByLabelText(/Categoría/), "FINE");
    await user.click(screen.getByRole("checkbox", { name: "Gravado" }));

    expect(onChange).toHaveBeenCalledWith("code", "C");
    expect(onChange).toHaveBeenCalledWith("category", "FINE");
    expect(onChange).toHaveBeenCalledWith("isTaxable", "true");
  });

  it("the error state describes each field with its message", () => {
    renderWithAdminI18n(<PaymentConceptForm {...props({ fieldErrors: { code: "Este dato es demasiado largo.", category: "Este dato es obligatorio." } })} />);

    expect(screen.getByLabelText(/Código/)).toHaveAccessibleDescription("Este dato es demasiado largo.");
    expect(screen.getByLabelText(/Categoría/)).toHaveAccessibleDescription("Este dato es obligatorio.");
    expect(screen.getByLabelText(/Nombre/)).toBeValid();
  });

  it("describes each checkbox with its help text", () => {
    renderWithAdminI18n(<PaymentConceptForm {...props()} />);

    expect(screen.getByRole("checkbox", { name: "Gravado" })).toHaveAccessibleDescription(/gravado con impuesto/);
  });
});
