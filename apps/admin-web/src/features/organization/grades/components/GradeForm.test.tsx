import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { formView } from "../../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../../test/render.tsx";
import { initialGradeValues } from "../grade-form.ts";
import { GradeForm, type GradeFormProps } from "./GradeForm.tsx";

// States. AW57, AW58: the form of a grade rendered with props alone. Loading: `form.submitting`, drawn by the
// dialog. Error: `fieldErrors` under each field. Empty: a create form with no modality to choose still offers its
// placeholder. Success: a create form, with the modality as a choice, and an edit form, with it fixed.
function props(override: Partial<GradeFormProps> = {}): GradeFormProps {
  return {
    mode: "create",
    values: initialGradeValues(),
    fieldErrors: {},
    modalityOptions: [{ value: "m1", label: "Primaria" }],
    modalityName: "",
    form: formView(),
    onChange: vi.fn(),
    onBlur: vi.fn(),
    onClose: vi.fn(),
    ...override,
  };
}

describe("GradeForm", () => {
  it("AW57: a new grade chooses its modality from the options and has a name and an order", () => {
    renderWithAdminI18n(<GradeForm {...props()} />);

    const dialog = screen.getByRole("dialog", { name: "Nuevo grado" });
    const modality = within(dialog).getByLabelText(/Modalidad/);
    expect(modality).toBeRequired();
    expect(within(modality).getAllByRole("option").map((option) => option.textContent)).toEqual(["Elija una opción", "Primaria"]);
    expect(within(dialog).getByLabelText(/Nombre/)).toBeRequired();
    expect(within(dialog).getByLabelText(/Orden/)).toHaveAttribute("inputmode", "numeric");
    expect(within(dialog).queryByLabelText("Estado")).not.toBeInTheDocument();
  });

  it("an existing grade shows its modality as a value that cannot be changed, and has its state", () => {
    renderWithAdminI18n(<GradeForm {...props({ mode: "edit", modalityName: "Primaria" })} />);

    const dialog = screen.getByRole("dialog", { name: "Editar grado" });
    const modality = within(dialog).getByLabelText(/Modalidad/);
    expect(modality).toHaveValue("Primaria");
    expect(modality).toHaveAttribute("readonly");
    expect(within(dialog).getByLabelText("Estado")).toBeInTheDocument();
  });

  it("with no modality to choose it offers only the placeholder", () => {
    renderWithAdminI18n(<GradeForm {...props({ modalityOptions: [] })} />);

    expect(within(screen.getByLabelText(/Modalidad/)).getAllByRole("option")).toHaveLength(1);
  });

  it("reports each change with the name of the field", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithAdminI18n(<GradeForm {...props({ onChange })} />);

    await user.selectOptions(screen.getByLabelText(/Modalidad/), "m1");
    await user.type(screen.getByLabelText(/Orden/), "3");

    expect(onChange).toHaveBeenCalledWith("modalityId", "m1");
    expect(onChange).toHaveBeenCalledWith("ordinal", "3");
  });

  it("the error state describes each field with its message", () => {
    renderWithAdminI18n(<GradeForm {...props({ fieldErrors: { ordinal: "Ingrese un número entero de cero en adelante." } })} />);

    expect(screen.getByLabelText(/Orden/)).toHaveAccessibleDescription("Ingrese un número entero de cero en adelante.");
    expect(screen.getByLabelText(/Nombre/)).toBeValid();
  });
});
