import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createRef } from "react";
import { describe, expect, it, vi } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { initialChargePlanValues, type ChargePlanValues } from "../charge-plan-form.ts";
import { ChargePlanForm, type ChargePlanFormProps } from "./ChargePlanForm.tsx";

// States. AW57, AW58: the form of a new plan rendered with props alone: no Query provider, no router, no request. Loading:
// `form.submitting`. Error: `fieldErrors` under their fields and `lookupFailure` when the options failed to load. Empty and
// success do not apply: it lists no data, and a saved form closes. The reference follows the scope.
const options = (label: string) => [{ value: `${label}-1`, label }];

function props(override: Partial<ChargePlanFormProps> = {}, values: Partial<ChargePlanValues> = {}): ChargePlanFormProps {
  return {
    values: { ...initialChargePlanValues(), ...values },
    fieldErrors: {},
    form: {
      summary: [],
      liveMessage: "",
      submitting: false,
      summaryRef: createRef<HTMLDivElement>(),
      alertRef: createRef<HTMLDivElement>(),
      onSubmit: vi.fn(),
    },
    yearOptions: options("Año lectivo 2027"),
    modalityOptions: options("Diurna"),
    gradeOptions: options("Primero"),
    sectionOptions: options("Primero A (Año lectivo 2027)"),
    onChange: vi.fn(),
    onBlur: vi.fn(),
    onClose: vi.fn(),
    ...override,
  };
}

const reference = (dialog: HTMLElement, label: RegExp) => within(within(dialog).getByLabelText(label)).getAllByRole("option").map((option) => option.textContent);

describe("ChargePlanForm", () => {
  it("is a named and described modal with the six fields, the frequency and the scope with their choices", () => {
    renderWithAdminI18n(<ChargePlanForm {...props()} />);

    const dialog = screen.getByRole("dialog", { name: "Nuevo plan de cobro" });
    for (const label of [/Nombre/, /Año lectivo/, /Ámbito/, /Grado/, /Periodicidad/, /Día de vencimiento/]) expect(within(dialog).getByLabelText(label)).toBeInTheDocument();
    expect(within(within(dialog).getByLabelText(/Periodicidad/)).getAllByRole("option").map((option) => option.textContent)).toEqual(["Mensual", "Bimestral", "Anual", "Única vez"]);
    expect(within(within(dialog).getByLabelText(/Ámbito/)).getAllByRole("option").map((option) => option.textContent)).toEqual(["Una modalidad", "Un grado", "Una sección"]);
  });

  it.each([
    ["MODALITY", /Modalidad/, ["Elija una opción", "Diurna"]],
    ["GRADE", /Grado/, ["Elija una opción", "Primero"]],
    ["SECTION", /Sección/, ["Elija una opción", "Primero A (Año lectivo 2027)"]],
  ] as const)("offers the choices of the scope %s as the reference", (scope, label, expected) => {
    renderWithAdminI18n(<ChargePlanForm {...props({}, { targetScope: scope })} />);

    expect(reference(screen.getByRole("dialog"), label)).toEqual(expected);
  });

  it("reports what is typed and chosen, and the blur of a field", async () => {
    const user = userEvent.setup();
    const view = props();
    renderWithAdminI18n(<ChargePlanForm {...view} />);

    await user.type(screen.getByLabelText(/Día de vencimiento/), "5");
    await user.selectOptions(screen.getByLabelText(/Ámbito/), "SECTION");
    await user.tab();

    expect(view.onChange).toHaveBeenCalledWith("dueDayOfMonth", "5");
    expect(view.onChange).toHaveBeenCalledWith("targetScope", "SECTION");
    expect(view.onBlur).toHaveBeenCalled();
  });

  it("shows the error of a field in its description, and the wait of a form that is being sent", () => {
    renderWithAdminI18n(
      <ChargePlanForm
        {...props({ fieldErrors: { dueDayOfMonth: "Escriba un día del mes entre 1 y 28, para que exista en todos los meses." }, form: { ...props().form, submitting: true } })}
      />,
    );

    expect(screen.getByLabelText(/Día de vencimiento/)).toHaveAccessibleDescription(/entre 1 y 28/);
    expect(screen.getByRole("button", { name: "Cancelar" })).toBeDisabled();
  });

  it("warns, with a retry, when the options failed to load", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderWithAdminI18n(<ChargePlanForm {...props({ lookupFailure: { onRetry } })} />);

    expect(screen.getByText("No se pudieron cargar las opciones de los filtros y de los formularios.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });
});
