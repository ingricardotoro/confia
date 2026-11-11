import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { formView } from "../../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../../test/render.tsx";
import { initialSectionValues } from "../section-form.ts";
import { SectionForm, type SectionFormProps } from "./SectionForm.tsx";

// States. AW57, AW58: the form of a section rendered with props alone. Loading: `form.submitting`, drawn by the
// dialog. Error: `fieldErrors` under each field. Empty: a create form with nothing to choose still offers its
// placeholders. Success: a create form, with the year and the grade as choices, and an edit form, with them fixed.
function props(override: Partial<SectionFormProps> = {}): SectionFormProps {
  return {
    mode: "create",
    values: initialSectionValues(),
    fieldErrors: {},
    yearOptions: [{ value: "y1", label: "Año 2026" }],
    gradeOptions: [{ value: "g1", label: "Primaria · Primero" }],
    yearName: "",
    gradeName: "",
    form: formView(),
    onChange: vi.fn(),
    onBlur: vi.fn(),
    onClose: vi.fn(),
    ...override,
  };
}

describe("SectionForm", () => {
  it("AW57: a new section chooses its year and grade, and has a name and an optional capacity", () => {
    renderWithAdminI18n(<SectionForm {...props()} />);

    const dialog = screen.getByRole("dialog", { name: "Nueva sección" });
    expect(within(dialog).getByLabelText(/Año lectivo/)).toBeRequired();
    expect(within(dialog).getByLabelText(/Grado/)).toBeRequired();
    expect(within(dialog).getByLabelText(/Nombre/)).toBeRequired();
    // The capacity is optional, and says what leaving it empty means.
    expect(within(dialog).getByLabelText(/Cupo/)).not.toBeRequired();
    expect(within(dialog).getByLabelText(/Cupo/)).toHaveAccessibleDescription("Déjelo vacío si la sección no tiene un límite de estudiantes.");
    expect(within(dialog).queryByLabelText("Estado")).not.toBeInTheDocument();
  });

  it("an existing section shows its year and its grade as values that cannot be changed, and has its state", () => {
    renderWithAdminI18n(<SectionForm {...props({ mode: "edit", yearName: "Año 2026", gradeName: "Primaria · Primero" })} />);

    const dialog = screen.getByRole("dialog", { name: "Editar sección" });
    expect(within(dialog).getByLabelText(/Año lectivo/)).toHaveValue("Año 2026");
    expect(within(dialog).getByLabelText(/Año lectivo/)).toHaveAttribute("readonly");
    expect(within(dialog).getByLabelText(/Grado/)).toHaveValue("Primaria · Primero");
    expect(within(dialog).getByLabelText(/Grado/)).toHaveAttribute("readonly");
    expect(within(dialog).getByLabelText("Estado")).toBeInTheDocument();
  });

  it("with nothing to choose it offers only the placeholders", () => {
    renderWithAdminI18n(<SectionForm {...props({ yearOptions: [], gradeOptions: [] })} />);

    expect(within(screen.getByLabelText(/Año lectivo/)).getAllByRole("option")).toHaveLength(1);
    expect(within(screen.getByLabelText(/Grado/)).getAllByRole("option")).toHaveLength(1);
  });

  it("reports each change with the name of the field", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithAdminI18n(<SectionForm {...props({ onChange })} />);

    await user.selectOptions(screen.getByLabelText(/Grado/), "g1");
    await user.type(screen.getByLabelText(/Cupo/), "3");

    expect(onChange).toHaveBeenCalledWith("gradeId", "g1");
    expect(onChange).toHaveBeenCalledWith("capacity", "3");
  });

  it("the error state describes the capacity with its message, after the help", () => {
    renderWithAdminI18n(<SectionForm {...props({ fieldErrors: { capacity: "Ingrese un número entero mayor que cero." } })} />);

    expect(screen.getByLabelText(/Cupo/)).toBeInvalid();
    expect(screen.getByLabelText(/Cupo/)).toHaveAccessibleDescription(/Ingrese un número entero mayor que cero\./);
  });
});
