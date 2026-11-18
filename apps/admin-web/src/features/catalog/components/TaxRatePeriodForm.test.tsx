import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { formView } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { initialTaxRatePeriodValues } from "../tax-rate-period-form.ts";
import { TaxRatePeriodForm, type TaxRatePeriodFormProps } from "./TaxRatePeriodForm.tsx";

// States. AW57, AW58: the form of a new validity rendered with props alone. Loading: `form.submitting`, drawn by the
// dialog. Error: `fieldErrors` under each field. Empty does not apply: a form lists no data. Success: the form with its
// percentage and dates.
function props(override: Partial<TaxRatePeriodFormProps> = {}): TaxRatePeriodFormProps {
  return {
    values: initialTaxRatePeriodValues(),
    fieldErrors: {},
    form: formView(),
    onChange: vi.fn(),
    onBlur: vi.fn(),
    onClose: vi.fn(),
    ...override,
  };
}

describe("TaxRatePeriodForm", () => {
  it("asks for the percentage and the start, and leaves the end optional", () => {
    renderWithAdminI18n(<TaxRatePeriodForm {...props()} />);

    const dialog = screen.getByRole("dialog", { name: "Nueva vigencia" });
    expect(within(dialog).getByLabelText(/Porcentaje/)).toBeRequired();
    expect(within(dialog).getByLabelText(/Vigente desde/)).toBeRequired();
    expect(within(dialog).getByLabelText(/Vigente desde/)).toHaveAttribute("type", "date");
    expect(within(dialog).getByLabelText(/Vigente hasta/)).not.toBeRequired();
  });

  it("tells how to write the percentage and what an empty end means, without any example figure", () => {
    renderWithAdminI18n(<TaxRatePeriodForm {...props()} />);

    expect(screen.getByLabelText(/Porcentaje/)).toHaveAccessibleDescription("Use punto decimal y hasta cuatro decimales. Debe ser mayor que cero y no pasar de 100.");
    expect(screen.getByLabelText(/Vigente hasta/)).toHaveAccessibleDescription(/Déjela vacía si la vigencia no tiene fecha de fin/);
  });

  it("reports each change with the name of the field, as the text typed", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithAdminI18n(<TaxRatePeriodForm {...props({ onChange })} />);

    await user.type(screen.getByLabelText(/Porcentaje/), "7");

    expect(onChange).toHaveBeenCalledWith("ratePercent", "7");
  });

  it("shows each error under its field, tied to the control", () => {
    renderWithAdminI18n(
      <TaxRatePeriodForm {...props({ fieldErrors: { ratePercent: "El porcentaje debe ser mayor que cero y no pasar de 100.", validTo: "La fecha de fin no puede ser anterior a la de inicio." } })} />,
    );

    expect(screen.getByLabelText(/Porcentaje/)).toBeInvalid();
    expect(screen.getByLabelText(/Porcentaje/)).toHaveAccessibleDescription(/no pasar de 100/);
    expect(screen.getByLabelText(/Vigente hasta/)).toHaveAccessibleDescription(/anterior a la de inicio/);
  });

  it("the loading state marks the save button as busy while the validity is sent", () => {
    renderWithAdminI18n(<TaxRatePeriodForm {...props({ form: formView({ submitting: true }) })} />);

    expect(screen.getByRole("button", { name: "Cancelar" })).toBeDisabled();
    expect(screen.getByRole("button", { name: /Guardar vigencia$/ })).toHaveAttribute("aria-busy", "true");
  });
});
