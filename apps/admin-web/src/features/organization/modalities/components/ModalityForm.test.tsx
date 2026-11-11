import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { formView } from "../../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../../test/render.tsx";
import { initialModalityValues } from "../modality-form.ts";
import { ModalityForm, type ModalityFormProps } from "./ModalityForm.tsx";

// States. AW57, AW58: the form of a modality rendered with props alone. Loading: `form.submitting`, drawn by the
// dialog. Error: `fieldErrors` under each field. Empty does not apply: a form lists no data. Success: a create
// form and an edit form, which differ in the state field.
function props(override: Partial<ModalityFormProps> = {}): ModalityFormProps {
  return {
    mode: "create",
    values: initialModalityValues(),
    fieldErrors: {},
    form: formView(),
    onChange: vi.fn(),
    onBlur: vi.fn(),
    onClose: vi.fn(),
    ...override,
  };
}

describe("ModalityForm", () => {
  it("AW57: a new modality has a code and a name, both required, and no state", () => {
    renderWithAdminI18n(<ModalityForm {...props()} />);

    const dialog = screen.getByRole("dialog", { name: "Nueva modalidad" });
    expect(within(dialog).getByLabelText(/Código/)).toBeRequired();
    expect(within(dialog).getByLabelText(/Nombre/)).toBeRequired();
    expect(within(dialog).queryByLabelText("Estado")).not.toBeInTheDocument();
  });

  it("an existing modality also has its state", () => {
    renderWithAdminI18n(<ModalityForm {...props({ mode: "edit" })} />);

    expect(within(screen.getByRole("dialog", { name: "Editar modalidad" })).getByLabelText("Estado")).toBeInTheDocument();
  });

  it("reports each change with the name of the field", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithAdminI18n(<ModalityForm {...props({ onChange })} />);

    await user.type(screen.getByLabelText(/Código/), "P");

    expect(onChange).toHaveBeenCalledWith("code", "P");
  });

  it("the error state describes each field with its message", () => {
    renderWithAdminI18n(<ModalityForm {...props({ fieldErrors: { code: "Este dato es demasiado largo." } })} />);

    expect(screen.getByLabelText(/Código/)).toHaveAccessibleDescription("Este dato es demasiado largo.");
    expect(screen.getByLabelText(/Nombre/)).toBeValid();
  });
});
