import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { formView } from "../../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../../test/render.tsx";
import { initialYearValues } from "../academic-year-form.ts";
import { AcademicYearForm, type AcademicYearFormProps } from "./AcademicYearForm.tsx";

// States. AW57, AW58: the form of an academic year rendered with props alone. Loading: `form.submitting`, drawn by
// the dialog. Error: `fieldErrors` under each field. Empty does not apply: a form lists no data. Success: a
// create form and an edit form, which differ in the stages offered and in the state field.
function props(override: Partial<AcademicYearFormProps> = {}): AcademicYearFormProps {
  return {
    mode: "create",
    values: initialYearValues(),
    fieldErrors: {},
    form: formView(),
    onChange: vi.fn(),
    onBlur: vi.fn(),
    onClose: vi.fn(),
    ...override,
  };
}

describe("AcademicYearForm", () => {
  it("AW57: a new year has a name, two native date fields and a stage, and no state", () => {
    renderWithAdminI18n(<AcademicYearForm {...props()} />);

    const dialog = screen.getByRole("dialog", { name: "Nuevo año lectivo" });
    expect(within(dialog).getByLabelText(/Nombre/)).toBeRequired();
    expect(within(dialog).getByLabelText(/Fecha de inicio/)).toHaveAttribute("type", "date");
    expect(within(dialog).getByLabelText(/Fecha de fin/)).toHaveAttribute("type", "date");
    expect(within(within(dialog).getByLabelText(/Etapa/)).getAllByRole("option")).toHaveLength(2);
    expect(within(dialog).queryByLabelText("Estado")).not.toBeInTheDocument();
  });

  it("an existing year offers the three stages and the state", () => {
    renderWithAdminI18n(<AcademicYearForm {...props({ mode: "edit" })} />);

    const dialog = screen.getByRole("dialog", { name: "Editar año lectivo" });
    expect(within(within(dialog).getByLabelText(/Etapa/)).getAllByRole("option")).toHaveLength(3);
    expect(within(dialog).getByLabelText("Estado")).toBeInTheDocument();
  });

  it("reports each change and each blur with the name of the field", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    const onBlur = vi.fn();
    renderWithAdminI18n(<AcademicYearForm {...props({ onChange, onBlur })} />);

    await user.type(screen.getByLabelText(/Nombre/), "A");
    await user.selectOptions(screen.getByLabelText(/Etapa/), "ACTIVE");
    await user.tab();

    expect(onChange).toHaveBeenCalledWith("name", "A");
    expect(onChange).toHaveBeenCalledWith("status", "ACTIVE");
    expect(onBlur).toHaveBeenCalledWith("status");
  });

  it("the error state describes each field with its message", () => {
    renderWithAdminI18n(
      <AcademicYearForm {...props({ fieldErrors: { name: "Este dato es obligatorio.", endDate: "La fecha de fin debe ser posterior a la de inicio." } })} />,
    );

    expect(screen.getByLabelText(/Nombre/)).toHaveAccessibleDescription("Este dato es obligatorio.");
    expect(screen.getByLabelText(/Fecha de fin/)).toHaveAccessibleDescription("La fecha de fin debe ser posterior a la de inicio.");
    expect(screen.getByLabelText(/Fecha de inicio/)).toBeValid();
  });
});
