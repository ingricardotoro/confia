import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { taxRate } from "../../../test/contract-responses.ts";
import { formView } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { initialTaxRateValues } from "../tax-rate-form.ts";
import { TaxRateForm, type TaxRateFormProps } from "./TaxRateForm.tsx";

// States. AW57, AW58: the form of a tax rate rendered with props alone. Loading: `form.submitting`, drawn by the dialog.
// Error: `fieldErrors` under each field. Empty does not apply: a form lists no data. Success: a create form and an edit
// form, which differ in the code (fixed once created).
function props(override: Partial<TaxRateFormProps> = {}): TaxRateFormProps {
  return {
    mode: "create",
    values: initialTaxRateValues(),
    fieldErrors: {},
    form: formView(),
    onChange: vi.fn(),
    onBlur: vi.fn(),
    onClose: vi.fn(),
    ...override,
  };
}

describe("TaxRateForm", () => {
  it("a new rate has a code and a name, both required, and no percentage", () => {
    renderWithAdminI18n(<TaxRateForm {...props()} />);

    const dialog = screen.getByRole("dialog", { name: "Nueva tasa de impuesto" });
    expect(within(dialog).getByLabelText(/Código/)).toBeRequired();
    expect(within(dialog).getByLabelText(/Nombre/)).toBeRequired();
    expect(within(dialog).queryByLabelText(/Porcentaje/)).not.toBeInTheDocument();
  });

  it("an existing rate shows its code as read only and its name as the one thing to edit", () => {
    renderWithAdminI18n(<TaxRateForm {...props({ mode: "edit", values: initialTaxRateValues(taxRate({ code: "IMP-A", name: "Impuesto de prueba A" })) })} />);

    const dialog = screen.getByRole("dialog", { name: "Editar tasa de impuesto" });
    expect(within(dialog).getByLabelText(/Código/)).toHaveValue("IMP-A");
    expect(within(dialog).getByLabelText(/Código/)).toHaveAttribute("readonly");
    expect(within(dialog).getByLabelText(/Nombre/)).toHaveValue("Impuesto de prueba A");
    expect(within(dialog).getByLabelText(/Nombre/)).not.toHaveAttribute("readonly");
  });

  it("reports each change with the name of the field, the blur, and the close", async () => {
    const user = userEvent.setup();
    const handlers = { onChange: vi.fn(), onBlur: vi.fn(), onClose: vi.fn() };
    renderWithAdminI18n(<TaxRateForm {...props(handlers)} />);

    await user.type(screen.getByLabelText(/Código/), "I");
    await user.type(screen.getByLabelText(/Nombre/), "N");
    await user.tab();
    await user.click(screen.getByRole("button", { name: "Cancelar" }));

    expect(handlers.onChange).toHaveBeenCalledWith("code", "I");
    expect(handlers.onChange).toHaveBeenCalledWith("name", "N");
    expect(handlers.onBlur).toHaveBeenCalledWith("name");
    expect(handlers.onClose).toHaveBeenCalled();
  });

  it("shows each error under its field, tied to the control", () => {
    renderWithAdminI18n(<TaxRateForm {...props({ fieldErrors: { code: "Ya existe una tasa con ese código." } })} />);

    expect(screen.getByLabelText(/Código/)).toBeInvalid();
    expect(screen.getByLabelText(/Código/)).toHaveAccessibleDescription(/Ya existe una tasa con ese código\./);
  });

  it("the loading state disables the save button while the rate is sent", () => {
    renderWithAdminI18n(<TaxRateForm {...props({ form: formView({ submitting: true }) })} />);

    expect(screen.getByRole("button", { name: "Cancelar" })).toBeDisabled();
    expect(screen.getByRole("button", { name: /Guardar$/ })).toHaveAttribute("aria-busy", "true");
  });
});
