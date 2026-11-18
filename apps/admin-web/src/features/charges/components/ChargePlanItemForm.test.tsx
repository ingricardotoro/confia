import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createRef } from "react";
import { describe, expect, it, vi } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { initialChargePlanItemValues } from "../charge-plan-item-form.ts";
import { ChargePlanItemForm, type ChargePlanItemFormProps } from "./ChargePlanItemForm.tsx";

// States. AW57, AW58: the form of a new item rendered with props alone: no Query provider, no router, no request. Loading:
// `form.submitting`. Error: `fieldErrors` under their fields and `lookupFailure` when the concepts failed to load. Empty:
// `noConcepts`, when no concept can be added. Success does not apply: a saved form closes.
function props(override: Partial<ChargePlanItemFormProps> = {}): ChargePlanItemFormProps {
  return {
    values: initialChargePlanItemValues(),
    fieldErrors: {},
    form: {
      summary: [],
      liveMessage: "",
      submitting: false,
      summaryRef: createRef<HTMLDivElement>(),
      alertRef: createRef<HTMLDivElement>(),
      onSubmit: vi.fn(),
    },
    conceptOptions: [
      { value: "c1", label: "Colegiatura" },
      { value: "c2", label: "Matrícula anual" },
    ],
    noConcepts: false,
    onChange: vi.fn(),
    onBlur: vi.fn(),
    onClose: vi.fn(),
    ...override,
  };
}

describe("ChargePlanItemForm", () => {
  it("is a named and described modal with the concept, the periods and the first day of the first period, and says the day is the first of a month", () => {
    renderWithAdminI18n(<ChargePlanItemForm {...props()} />);

    const dialog = screen.getByRole("dialog", { name: "Agregar concepto" });
    expect(dialog).toHaveAccessibleDescription(/Solo se ofrecen los conceptos activos que el plan todavía no tiene/);
    expect(within(within(dialog).getByLabelText(/Concepto/)).getAllByRole("option").map((option) => option.textContent)).toEqual([
      "Elija una opción",
      "Colegiatura",
      "Matrícula anual",
    ]);
    expect(within(dialog).getByLabelText(/Cantidad de períodos/)).toHaveAccessibleDescription(/del 1 al 36/);
    expect(within(dialog).getByLabelText(/Primer día del primer período/)).toHaveAccessibleDescription(/Elija el día 1 de un mes/);
  });

  it("reports what is typed and chosen", async () => {
    const user = userEvent.setup();
    const view = props();
    renderWithAdminI18n(<ChargePlanItemForm {...view} />);

    await user.selectOptions(screen.getByLabelText(/Concepto/), "c2");
    await user.type(screen.getByLabelText(/Cantidad de períodos/), "7");

    expect(view.onChange).toHaveBeenCalledWith("paymentConceptId", "c2");
    expect(view.onChange).toHaveBeenCalledWith("periodsCount", "7");
  });

  it("shows the error of a field in its description, and the wait of a form that is being sent", () => {
    renderWithAdminI18n(
      <ChargePlanItemForm
        {...props({
          fieldErrors: { firstPeriodOn: "Elija el primer día de un mes, por ejemplo el 1 de febrero. Los períodos siempre empiezan el día 1." },
          form: { ...props().form, submitting: true },
        })}
      />,
    );

    expect(screen.getByLabelText(/Primer día del primer período/)).toHaveAccessibleDescription(/Elija el primer día de un mes/);
    expect(screen.getByRole("button", { name: "Cancelar" })).toBeDisabled();
  });

  it("says there is nothing to add when no concept can be added, and warns, with a retry, when the concepts failed to load", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderWithAdminI18n(<ChargePlanItemForm {...props({ conceptOptions: [], noConcepts: true, lookupFailure: { onRetry } })} />);

    expect(screen.getByText(/No hay conceptos de pago activos para agregar/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });
});
