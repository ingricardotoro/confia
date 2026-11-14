import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { formView } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { initialStudentValues } from "../student-form.ts";
import { student } from "../../../test/contract-responses.ts";
import { StudentForm, type StudentFormProps } from "./StudentForm.tsx";

// States. AW57, AW58: the form of a student rendered with props alone. Loading: `form.submitting`, drawn by the dialog.
// Error: `fieldErrors` under each field. Empty: a create form with nothing typed. Success: a create form, with the code
// optional, and an edit form, with the code fixed and the document in its three separate acts.
const WITH_DOCUMENT = student({ hasNationalId: true });

function props(override: Partial<StudentFormProps> = {}): StudentFormProps {
  return {
    mode: "create",
    values: initialStudentValues(),
    fieldErrors: {},
    form: formView(),
    onChange: vi.fn(),
    onBlur: vi.fn(),
    onClose: vi.fn(),
    ...override,
  };
}

const editProps = (override: Partial<StudentFormProps> = {}) =>
  props({ mode: "edit", values: initialStudentValues(WITH_DOCUMENT), row: WITH_DOCUMENT, ...override });

describe("StudentForm", () => {
  it("AW57: a new student has an optional code that says it is generated, the required fields and an optional document", () => {
    renderWithAdminI18n(<StudentForm {...props()} />);

    const dialog = screen.getByRole("dialog", { name: "Nuevo estudiante" });
    const code = within(dialog).getByLabelText(/^Código/);
    expect(code).not.toBeRequired();
    expect(code).not.toHaveAttribute("readonly");
    expect(code).toHaveAccessibleDescription("Si lo deja vacío, el sistema genera uno.");
    for (const label of [/Nombres/, /Apellidos/, /Fecha de nacimiento/, /Sexo/]) expect(within(dialog).getByLabelText(label)).toBeRequired();
    expect(within(dialog).getByLabelText(/Fecha de nacimiento/)).toHaveAttribute("type", "date");
    expect(within(within(dialog).getByLabelText(/Sexo/)).getAllByRole("option").map((option) => option.textContent)).toEqual([
      "Elija una opción",
      "Femenino",
      "Masculino",
      "No especificado",
    ]);
    expect(within(dialog).getByLabelText(/Documento de identidad/)).not.toBeRequired();
    // The edit-only parts are not there.
    expect(within(dialog).queryByLabelText("Documento actual")).not.toBeInTheDocument();
    expect(within(dialog).queryByRole("checkbox")).not.toBeInTheDocument();
    expect(within(dialog).queryByLabelText("Estado")).not.toBeInTheDocument();
  });

  it("an existing student shows the code fixed, the document only as registered and apart from its replacement, and the state", () => {
    renderWithAdminI18n(<StudentForm {...editProps()} />);

    const dialog = screen.getByRole("dialog", { name: "Editar estudiante" });
    expect(within(dialog).getByLabelText(/^Código/)).toHaveAttribute("readonly");
    expect(within(dialog).getByLabelText("Documento actual")).toHaveValue("Registrado");
    expect(within(dialog).getByLabelText("Documento actual")).toHaveAttribute("readonly");
    const replace = within(dialog).getByLabelText("Reemplazar documento");
    expect(replace).toHaveValue("");
    expect(replace).toHaveAccessibleDescription(/Déjelo vacío para conservar el documento actual/);
    const remove = within(dialog).getByRole("checkbox", { name: "Quitar documento" });
    expect(remove).not.toBeChecked();
    expect(remove).toHaveAccessibleDescription(/Si no la marca, el documento no se toca/);
    expect(within(dialog).getByLabelText("Estado")).toHaveValue("ACTIVE");
  });

  it("the id and the personal data inputs turn off autofill, autocorrect and spell checking", () => {
    renderWithAdminI18n(<StudentForm {...props()} />);

    const dialog = screen.getByRole("dialog", { name: "Nuevo estudiante" });
    for (const label of [/Nombres/, /Apellidos/, /Fecha de nacimiento/, /Documento de identidad/]) {
      const input = within(dialog).getByLabelText(label);
      expect(input).toHaveAttribute("spellcheck", "false");
      expect(input).toHaveAttribute("autocomplete", "off");
      expect(input).toHaveAttribute("autocorrect", "off");
    }
  });

  it("the replacement document input of an edit turns them off too", () => {
    renderWithAdminI18n(<StudentForm {...editProps()} />);

    const input = screen.getByLabelText("Reemplazar documento");
    expect(input).toHaveAttribute("spellcheck", "false");
    expect(input).toHaveAttribute("autocomplete", "off");
    expect(input).toHaveAttribute("autocorrect", "off");
  });

  it("without a stored document it shows that there is none and offers no removal", () => {
    const row = student();
    renderWithAdminI18n(<StudentForm {...editProps({ values: initialStudentValues(row), row })} />);

    expect(screen.getByLabelText("Documento actual")).toHaveValue("Sin documento");
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
  });

  it("checking the removal reports it and drops what was typed to replace; while checked the replacement is disabled", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    const { rerender } = renderWithAdminI18n(<StudentForm {...editProps({ onChange })} />);

    await user.click(screen.getByRole("checkbox", { name: "Quitar documento" }));

    expect(onChange).toHaveBeenCalledWith("removeNationalId", "true");
    expect(onChange).toHaveBeenCalledWith("nationalId", "");
    rerender(<StudentForm {...editProps({ onChange, values: { ...initialStudentValues(WITH_DOCUMENT), removeNationalId: "true" } })} />);
    expect(screen.getByRole("checkbox", { name: "Quitar documento" })).toBeChecked();
    expect(screen.getByLabelText("Reemplazar documento")).toBeDisabled();
  });

  it("unchecking the removal reports it without touching the replacement", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithAdminI18n(<StudentForm {...editProps({ onChange, values: { ...initialStudentValues(WITH_DOCUMENT), removeNationalId: "true" } })} />);

    await user.click(screen.getByRole("checkbox", { name: "Quitar documento" }));

    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange).toHaveBeenCalledWith("removeNationalId", "false");
  });

  it("shows the error of each field under it, tied to it", () => {
    renderWithAdminI18n(
      <StudentForm
        {...props({
          fieldErrors: { firstName: "Este dato es obligatorio.", nationalId: "El documento debe tener de 8 a 20 letras, números o guiones." },
        })}
      />,
    );

    expect(screen.getByLabelText(/Nombres/)).toBeInvalid();
    expect(screen.getByLabelText(/Nombres/)).toHaveAccessibleDescription("Este dato es obligatorio.");
    expect(screen.getByLabelText(/Documento de identidad/)).toHaveAccessibleDescription(/El documento debe tener de 8 a 20/);
    expect(screen.getByLabelText(/Apellidos/)).not.toBeInvalid();
  });

  it("reports each change and each blur by field", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    const onBlur = vi.fn();
    renderWithAdminI18n(<StudentForm {...props({ onChange, onBlur })} />);

    await user.type(screen.getByLabelText(/Nombres/), "A");
    await user.tab();
    await user.selectOptions(screen.getByLabelText(/Sexo/), "M");

    expect(onChange).toHaveBeenCalledWith("firstName", "A");
    expect(onBlur).toHaveBeenCalledWith("firstName");
    expect(onChange).toHaveBeenCalledWith("sex", "M");
  });

  it("draws the busy state of the dialog while it saves and closes with Cancel", async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    const { rerender } = renderWithAdminI18n(<StudentForm {...props({ onClose })} />);
    await user.click(screen.getByRole("button", { name: "Cancelar" }));
    expect(onClose).toHaveBeenCalledTimes(1);

    rerender(<StudentForm {...props({ onClose, form: formView({ submitting: true, liveMessage: "Guardando los datos." }) })} />);

    expect(screen.getByRole("button", { name: /Guardar/ })).toHaveAttribute("aria-busy", "true");
    expect(screen.getByText("Guardando los datos.")).toBeInTheDocument();
  });
});
