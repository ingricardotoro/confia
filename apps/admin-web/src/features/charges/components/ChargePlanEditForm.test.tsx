import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createRef } from "react";
import { describe, expect, it, vi } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { ChargePlanEditForm, type ChargePlanEditFormProps } from "./ChargePlanEditForm.tsx";

// States. AW57, AW58: the form of the edit of a plan rendered with props alone: no Query provider, no router, no request.
// Loading: `form.submitting`. Error: `fieldErrors` under their fields. Empty and success do not apply: it lists no data, and a
// saved form closes. `frozen` is the state of an active plan: only the name can change.
function props(override: Partial<ChargePlanEditFormProps> = {}): ChargePlanEditFormProps {
  return {
    values: { name: "Primaria 2027", frequency: "MONTHLY", dueDayOfMonth: "5" },
    fieldErrors: {},
    form: {
      summary: [],
      liveMessage: "",
      submitting: false,
      summaryRef: createRef<HTMLDivElement>(),
      alertRef: createRef<HTMLDivElement>(),
      onSubmit: vi.fn(),
    },
    frozen: false,
    onChange: vi.fn(),
    onBlur: vi.fn(),
    onClose: vi.fn(),
    ...override,
  };
}

describe("ChargePlanEditForm", () => {
  it("is a named and described modal with the name, the frequency and the due day, and not the year, the scope or the target", async () => {
    const user = userEvent.setup();
    const view = props();
    renderWithAdminI18n(<ChargePlanEditForm {...view} />);

    expect(screen.getByRole("dialog", { name: "Editar plan de cobro" })).toHaveAccessibleDescription(/Cambie el nombre, la periodicidad o el día de vencimiento/);
    expect(screen.getByLabelText(/Nombre/)).toHaveValue("Primaria 2027");
    expect(screen.getByLabelText(/Periodicidad/)).toHaveValue("MONTHLY");
    expect(screen.getByLabelText(/Día de vencimiento/)).toHaveValue("5");
    expect(screen.queryByLabelText(/Año lectivo|Ámbito|Grado/)).not.toBeInTheDocument();
    await user.type(screen.getByLabelText(/Nombre/), "x");
    expect(view.onChange).toHaveBeenCalledWith("name", "Primaria 2027x");
  });

  it("a frozen plan shows the frequency and the due day and cannot change them, and says why", () => {
    renderWithAdminI18n(<ChargePlanEditForm {...props({ frozen: true })} />);

    expect(screen.getByRole("dialog")).toHaveAccessibleDescription(/Un plan activo está congelado: solo puede cambiar el nombre/);
    expect(screen.getByLabelText(/Nombre/)).toBeEnabled();
    expect(screen.getByLabelText(/Periodicidad/)).toBeDisabled();
    expect(screen.getByLabelText(/Día de vencimiento/)).toBeDisabled();
  });

  it("shows the error of a field, and the wait of a form that is being sent", () => {
    renderWithAdminI18n(
      <ChargePlanEditForm {...props({ fieldErrors: { name: "Este dato es obligatorio." }, form: { ...props().form, submitting: true } })} />,
    );

    expect(screen.getByLabelText(/Nombre/)).toHaveAccessibleDescription(/Este dato es obligatorio\./);
    expect(screen.getByRole("button", { name: "Cancelar" })).toBeDisabled();
  });
});
