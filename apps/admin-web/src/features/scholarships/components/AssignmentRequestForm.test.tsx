import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { formView } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { initialAssignmentValues } from "../assignment-model.ts";
import { AssignmentRequestForm, type AssignmentRequestFormProps } from "./AssignmentRequestForm.tsx";

// States. AW57, AW58: The request of an assignment rendered with props alone. Loading: `form.submitting`, drawn by the dialog. Error: `fieldErrors` under each field
// and the failure of the lookup of discounts with its retry. Empty: no active agreement discount to choose. Success: the empty form with every value
// required, and the one with the student already chosen.
const picker = {
  controlId: "assignment-student",
  draft: "",
  onDraftChange: vi.fn(),
  tooShort: false,
  search: "idle",
  results: [],
  onRetry: vi.fn(),
  chosenPending: false,
  onChoose: vi.fn(),
  onClear: vi.fn(),
} as const;

function props(override: Partial<AssignmentRequestFormProps> = {}): AssignmentRequestFormProps {
  return {
    values: initialAssignmentValues(),
    fieldErrors: {},
    form: formView(),
    student: { picker },
    discountOptions: [{ value: "d1", label: "CONV20 · Convenio con la empresa" }],
    noDiscounts: false,
    onChange: vi.fn(),
    onBlur: vi.fn(),
    onClose: vi.fn(),
    ...override,
  };
}

describe("AssignmentRequestForm", () => {
  it("asks for the student, the agreement discount and the two days of the validity, all required, and says that the assignment is born pending", () => {
    renderWithAdminI18n(<AssignmentRequestForm {...props()} />);

    const dialog = screen.getByRole("dialog", { name: "Solicitar una asignación" });
    expect(within(dialog).getByRole("combobox", { name: /^Estudiante/ })).toBeInTheDocument();
    expect(within(dialog).getByLabelText(/^Descuento de convenio/)).toBeRequired();
    expect(within(dialog).getByLabelText(/^Vigente desde/)).toBeRequired();
    expect(within(dialog).getByLabelText(/^Vigente hasta/)).toBeRequired();
    expect(within(dialog).getByText(/Los cargos ya emitidos no cambian/)).toBeInTheDocument();
  });

  it("shows the chosen student with how to change them, and reports each change with the name of the field", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    const onClear = vi.fn();
    renderWithAdminI18n(<AssignmentRequestForm {...props({ onChange, student: { picker, chosen: { label: "Pérez, Ana (EST-0001)", onClear } } })} />);

    const dialog = screen.getByRole("dialog");
    expect(within(dialog).queryByRole("combobox", { name: /^Estudiante/ })).not.toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Cambiar estudiante" }));
    await user.selectOptions(within(dialog).getByLabelText(/^Descuento de convenio/), "CONV20 · Convenio con la empresa");
    await user.type(within(dialog).getByLabelText(/^Vigente desde/), "2027-01-01");

    expect(onClear).toHaveBeenCalled();
    expect(onChange).toHaveBeenCalledWith("discountId", "d1");
    expect(onChange).toHaveBeenCalledWith("validFrom", "2027-01-01");
  });

  it("shows the error of each field in words, and the one of the student under the search", () => {
    renderWithAdminI18n(
      <AssignmentRequestForm {...props({ fieldErrors: { studentId: "Este dato es obligatorio.", validTo: "La fecha de fin no puede ser anterior a la de inicio." } })} />,
    );

    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText("Este dato es obligatorio.")).toBeInTheDocument();
    expect(within(dialog).getByLabelText(/^Vigente hasta/)).toBeInvalid();
    expect(within(dialog).getByText("La fecha de fin no puede ser anterior a la de inicio.")).toBeInTheDocument();
  });

  it("says there is no active agreement discount, and offers to retry a failed lookup", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderWithAdminI18n(<AssignmentRequestForm {...props({ discountOptions: [], noDiscounts: true, lookupFailure: { onRetry } })} />);

    expect(screen.getByText(/No hay descuentos de convenio activos/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));
    expect(onRetry).toHaveBeenCalled();
  });
});
