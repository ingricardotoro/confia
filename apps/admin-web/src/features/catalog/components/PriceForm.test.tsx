import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { formView } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { initialPriceValues } from "../price-form.ts";
import { PriceForm, type PriceFormProps } from "./PriceForm.tsx";

// States. AW57, AW58: the form of a new price or validity rendered with props alone. Loading: `form.submitting`, drawn by
// the dialog. Error: `fieldErrors` under each field, `lookupFailure` for the concepts. Empty: `noActiveConcepts`, a note
// that there is nothing to price yet. Success: the form with its concepts, amount, currency and dates.
function props(override: Partial<PriceFormProps> = {}): PriceFormProps {
  return {
    values: initialPriceValues(),
    fieldErrors: {},
    form: formView(),
    conceptOptions: [{ value: "c-1", label: "Colegiatura" }],
    noActiveConcepts: false,
    onChange: vi.fn(),
    onBlur: vi.fn(),
    onClose: vi.fn(),
    ...override,
  };
}

describe("PriceForm", () => {
  it("asks for the concept, the amount, the currency and the start, and leaves the end optional", () => {
    renderWithAdminI18n(<PriceForm {...props()} />);

    const dialog = screen.getByRole("dialog", { name: "Nuevo precio / nueva vigencia" });
    expect(within(dialog).getByLabelText(/Concepto/)).toBeRequired();
    expect(within(dialog).getByLabelText(/Importe/)).toBeRequired();
    expect(within(dialog).getByLabelText(/Moneda/)).toHaveValue("HNL");
    expect(within(dialog).getByLabelText(/Vigente desde/)).toBeRequired();
    expect(within(dialog).getByLabelText(/Vigente hasta/)).not.toBeRequired();
    expect(within(dialog).getByLabelText(/Vigente desde/)).toHaveAttribute("type", "date");
  });

  it("offers only the concepts it is given and the two currencies", () => {
    renderWithAdminI18n(<PriceForm {...props()} />);

    expect(within(screen.getByLabelText(/Concepto/)).getAllByRole("option").map((option) => option.textContent)).toEqual(["Elija una opción", "Colegiatura"]);
    expect(within(screen.getByLabelText(/Moneda/)).getAllByRole("option").map((option) => option.textContent)).toEqual([
      "Lempira (HNL)",
      "Dólar estadounidense (USD)",
    ]);
  });

  it("tells how to write the amount and what an empty end means", () => {
    renderWithAdminI18n(<PriceForm {...props()} />);

    expect(screen.getByLabelText(/Importe/)).toHaveAccessibleDescription(/punto decimal y hasta cuatro decimales/);
    expect(screen.getByLabelText(/Vigente hasta/)).toHaveAccessibleDescription(/Déjela vacía si el precio no tiene fecha de fin/);
  });

  it("reports each change with the name of the field and the text typed, the amount included", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithAdminI18n(<PriceForm {...props({ onChange })} />);

    await user.selectOptions(screen.getByLabelText(/Concepto/), "c-1");
    await user.type(screen.getByLabelText(/Importe/), "1");
    await user.selectOptions(screen.getByLabelText(/Moneda/), "USD");

    expect(onChange).toHaveBeenCalledWith("paymentConceptId", "c-1");
    expect(onChange).toHaveBeenCalledWith("amount", "1");
    expect(onChange).toHaveBeenCalledWith("currency", "USD");
  });

  it("the error state describes each field with its message", () => {
    renderWithAdminI18n(
      <PriceForm {...props({ fieldErrors: { amount: "El importe debe ser mayor que cero.", validTo: "La fecha de fin no puede ser anterior a la de inicio." } })} />,
    );

    expect(screen.getByLabelText(/Importe/)).toHaveAccessibleDescription(/El importe debe ser mayor que cero\./);
    expect(screen.getByLabelText(/Vigente hasta/)).toHaveAccessibleDescription(/La fecha de fin no puede ser anterior/);
  });

  it("the empty state says there is no active concept to price", () => {
    renderWithAdminI18n(<PriceForm {...props({ conceptOptions: [], noActiveConcepts: true })} />);

    expect(screen.getByText(/No hay conceptos de pago activos/)).toBeInTheDocument();
  });

  it("warns, with a retry, when the concepts failed to load", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderWithAdminI18n(<PriceForm {...props({ lookupFailure: { onRetry } })} />);

    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(onRetry).toHaveBeenCalledTimes(1);
  });
});
