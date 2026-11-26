import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { formView } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { initialAwardValues } from "../award-model.ts";
import { AwardRequestForm, type AwardRequestFormProps } from "./AwardRequestForm.tsx";

// States. AW57, AW58: the request of an award rendered with props alone. Loading: `form.submitting`, drawn by the dialog. Error: `fieldErrors` under
// each field and the failure of the lookups with its retry. Empty: no active scholarship to award. Success: the student is a search while none is chosen
// and the chosen one afterwards, and the form says that the award is born pending.
const picker = {
  controlId: "award-student",
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

function props(override: Partial<AwardRequestFormProps> = {}): AwardRequestFormProps {
  return {
    values: initialAwardValues(),
    fieldErrors: {},
    form: formView(),
    student: { picker },
    scholarshipOptions: [{ value: "s1", label: "BECA25 · Beca de excelencia" }],
    yearOptions: [{ value: "y1", label: "Año lectivo 2027" }],
    noScholarships: false,
    onChange: vi.fn(),
    onBlur: vi.fn(),
    onClose: vi.fn(),
    ...override,
  };
}

describe("AwardRequestForm", () => {
  it("AW57: asks for the student, the scholarship, the year and the two days of the validity, all required, and says that the award is born pending", () => {
    renderWithAdminI18n(<AwardRequestForm {...props()} />);

    const dialog = screen.getByRole("dialog", { name: "Solicitar un otorgamiento" });
    expect(within(dialog).getByRole("combobox", { name: /^Estudiante/ })).toBeInTheDocument();
    expect(within(dialog).getByLabelText(/^Beca/)).toBeRequired();
    expect(within(dialog).getByLabelText(/^Año lectivo/)).toBeRequired();
    expect(within(dialog).getByLabelText(/^Vigente desde/)).toBeRequired();
    expect(within(dialog).getByLabelText(/^Vigente hasta/)).toBeRequired();
    expect(within(dialog).getByText(/Los cargos ya emitidos no cambian/)).toBeInTheDocument();
  });

  it("AW57: shows the chosen student with how to change them, and reports each change with the name of the field", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    const onClear = vi.fn();
    renderWithAdminI18n(<AwardRequestForm {...props({ onChange, student: { picker, chosen: { label: "Pérez, Ana (EST-0001)", onClear } } })} />);

    const dialog = screen.getByRole("dialog");
    expect(within(dialog).queryByRole("combobox", { name: /^Estudiante/ })).not.toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Cambiar estudiante" }));
    await user.selectOptions(within(dialog).getByLabelText(/^Beca/), "BECA25 · Beca de excelencia");
    await user.type(within(dialog).getByLabelText(/^Vigente desde/), "2027-01-01");

    expect(onClear).toHaveBeenCalled();
    expect(onChange).toHaveBeenCalledWith("scholarshipId", "s1");
    expect(onChange).toHaveBeenCalledWith("validFrom", "2027-01-01");
  });

  it("AW57: shows the error of each field in words, and the one of the student under the search", () => {
    renderWithAdminI18n(
      <AwardRequestForm {...props({ fieldErrors: { studentId: "Este dato es obligatorio.", validTo: "La fecha de fin no puede ser anterior a la de inicio." } })} />,
    );

    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText("Este dato es obligatorio.")).toBeInTheDocument();
    expect(within(dialog).getByLabelText(/^Vigente hasta/)).toBeInvalid();
    expect(within(dialog).getByText("La fecha de fin no puede ser anterior a la de inicio.")).toBeInTheDocument();
  });

  it("AW57: says there is no active scholarship, and offers to retry a failed lookup", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderWithAdminI18n(<AwardRequestForm {...props({ scholarshipOptions: [], noScholarships: true, lookupFailure: { onRetry } })} />);

    expect(screen.getByText(/No hay becas activas/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));
    expect(onRetry).toHaveBeenCalled();
  });
});
