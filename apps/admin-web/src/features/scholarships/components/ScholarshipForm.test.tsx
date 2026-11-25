import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { scholarship } from "../../../test/contract-responses.ts";
import { formView } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { initialScholarshipValues } from "../scholarship-form.ts";
import { ScholarshipForm, type ScholarshipFormProps } from "./ScholarshipForm.tsx";

// States. AW57, AW58: the form of a scholarship rendered with props alone. Loading: `form.submitting`, drawn by the dialog. Error: `fieldErrors` under each
// field and the group of concepts, and the failure of the lookup of concepts with its retry. Empty: no active concept to choose. Success: a create form
// and an edit form, which differ in the code (fixed once created) and in the benefit (read only once an award was approved).
const OPTIONS = [
  { value: "00000000-0000-4000-8000-000000007001", label: "MENS · Mensualidad" },
  { value: "00000000-0000-4000-8000-000000007002", label: "MATR · Matrícula" },
];

function props(override: Partial<ScholarshipFormProps> = {}): ScholarshipFormProps {
  return {
    mode: "create",
    values: initialScholarshipValues(),
    fieldErrors: {},
    form: formView(),
    conceptOptions: OPTIONS,
    noConcepts: false,
    benefitLocked: false,
    onChange: vi.fn(),
    onBlur: vi.fn(),
    onClose: vi.fn(),
    ...override,
  };
}

describe("ScholarshipForm", () => {
  it("AW57: a new scholarship has a code, a name and a type of benefit, all required, and the value only once the type is chosen", () => {
    const { rerender } = renderWithAdminI18n(<ScholarshipForm {...props()} />);

    const dialog = screen.getByRole("dialog", { name: "Crear una beca" });
    expect(within(dialog).getByLabelText(/^Código/)).toBeRequired();
    expect(within(dialog).getByLabelText(/^Nombre/)).toBeRequired();
    expect(within(dialog).getByLabelText(/^Tipo de beneficio/)).toBeRequired();
    expect(within(dialog).queryByLabelText(/^Porcentaje/)).not.toBeInTheDocument();
    expect(within(dialog).queryByLabelText(/^Monto fijo/)).not.toBeInTheDocument();

    rerender(<ScholarshipForm {...props({ values: { ...initialScholarshipValues(), benefitType: "FIXED_AMOUNT" } })} />);
    expect(within(screen.getByRole("dialog")).getByLabelText(/^Monto fijo por cargo/)).toBeRequired();
    expect(within(screen.getByRole("dialog")).queryByLabelText(/^Porcentaje/)).not.toBeInTheDocument();
  });

  it("AW57: an existing scholarship shows its code as read only, its benefit as it holds it and its concepts chosen", () => {
    const row = scholarship({ code: "BECA25", percent: "25.00" });
    renderWithAdminI18n(<ScholarshipForm {...props({ mode: "edit", values: initialScholarshipValues(row) })} />);

    const dialog = screen.getByRole("dialog", { name: "Editar la beca" });
    expect(within(dialog).getByLabelText(/^Código/)).toHaveAttribute("readonly");
    expect(within(dialog).getByLabelText(/^Porcentaje/)).toHaveValue("25.00");
    expect(within(dialog).getByRole("checkbox", { name: "MENS · Mensualidad" })).toBeChecked();
    expect(within(dialog).getByRole("checkbox", { name: "MATR · Matrícula" })).not.toBeChecked();
  });

  it("AW57: a scholarship with approved awards shows its type and value as read only, with the reason", () => {
    const row = scholarship({ benefitLocked: true });
    renderWithAdminI18n(<ScholarshipForm {...props({ mode: "edit", benefitLocked: true, values: initialScholarshipValues(row) })} />);

    const dialog = screen.getByRole("dialog", { name: "Editar la beca" });
    expect(within(dialog).getByText(/su tipo y su valor no se pueden cambiar/)).toBeInTheDocument();
    expect(within(dialog).getByLabelText(/^Porcentaje/)).toHaveAttribute("readonly");
    expect(within(dialog).getByLabelText(/^Tipo de beneficio/)).toHaveValue("Porcentaje");
  });

  it("AW57: reports each change with the name of the field, and the concepts as one text of ids in the order of the options", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithAdminI18n(<ScholarshipForm {...props({ onChange, values: { ...initialScholarshipValues(), conceptIds: OPTIONS[1]?.value ?? "" } })} />);

    await user.type(screen.getByLabelText(/^Código/), "B");
    await user.selectOptions(screen.getByLabelText(/^Tipo de beneficio/), "PERCENTAGE");
    await user.click(screen.getByRole("checkbox", { name: "MENS · Mensualidad" }));

    expect(onChange).toHaveBeenCalledWith("code", "B");
    expect(onChange).toHaveBeenCalledWith("benefitType", "PERCENTAGE");
    expect(onChange).toHaveBeenCalledWith("conceptIds", `${OPTIONS[0]?.value ?? ""},${OPTIONS[1]?.value ?? ""}`);
  });

  it("AW57: shows the error of each field in words, and the one of the concepts under their group", () => {
    renderWithAdminI18n(
      <ScholarshipForm {...props({ values: { ...initialScholarshipValues(), benefitType: "PERCENTAGE" }, fieldErrors: { name: "Este dato es obligatorio.", conceptIds: "Elija al menos un concepto." } })} />,
    );

    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByLabelText(/^Nombre/)).toBeInvalid();
    expect(within(dialog).getByText("Este dato es obligatorio.")).toBeInTheDocument();
    expect(within(within(dialog).getByRole("group", { name: "Conceptos que cubre" })).getByText("Elija al menos un concepto.")).toBeInTheDocument();
  });

  it("AW57: says there is no active concept to choose, and offers to retry a failed lookup of concepts", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderWithAdminI18n(<ScholarshipForm {...props({ conceptOptions: [], noConcepts: true, conceptLookupFailure: { onRetry } })} />);

    expect(screen.getByText(/No hay conceptos de pago activos/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));
    expect(onRetry).toHaveBeenCalled();
  });
});
