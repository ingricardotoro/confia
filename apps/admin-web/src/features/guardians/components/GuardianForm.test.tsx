import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { formView } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { initialGuardianValues } from "../guardian-form.ts";
import { GuardianForm, type GuardianFormProps } from "./GuardianForm.tsx";

// States. AW57, AW58: create (the document and the RTN are typed), edit (they are only shown as registered or not, with a box
// to replace and a checkbox to remove), the error summary and the alert of a failed send, and the wait while saving. Loading,
// empty and success belong to the container and the list.
function props(override: Partial<GuardianFormProps> = {}): GuardianFormProps {
  return {
    mode: "create",
    values: initialGuardianValues(),
    fieldErrors: {},
    form: formView(),
    onChange: vi.fn(),
    onBlur: vi.fn(),
    onClose: vi.fn(),
    ...override,
  };
}

describe("GuardianForm", () => {
  it("creates: asks for the names, and the document and the RTN are plain boxes marked as personal data", () => {
    renderWithAdminI18n(<GuardianForm {...props()} />);

    const dialog = screen.getByRole("dialog", { name: "Nuevo encargado" });
    expect(within(dialog).getByLabelText(/Nombres/)).toBeRequired();
    for (const label of [/Documento de identidad/, /^RTN/, /Correo/, /Teléfono/, /Dirección/, /Nombres/]) {
      const field = within(dialog).getByLabelText(label);
      expect(field).toHaveAttribute("autocomplete", "off");
      expect(field).toHaveAttribute("spellcheck", "false");
    }
    expect(within(dialog).queryByLabelText("Estado")).not.toBeInTheDocument();
    expect(within(dialog).queryByLabelText("Código")).not.toBeInTheDocument();
  });

  it("edits: shows the code, only whether a document and an RTN exist, and a box and a checkbox for each", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithAdminI18n(<GuardianForm {...props({ mode: "edit", row: { guardianCode: "P-000003", hasNationalId: true, hasRtn: false }, onChange })} />);
    const dialog = screen.getByRole("dialog", { name: "Editar encargado" });

    expect(within(dialog).getByLabelText("Código")).toHaveValue("P-000003");
    expect(within(dialog).getByLabelText("Documento actual")).toHaveValue("Registrado");
    expect(within(dialog).getByLabelText("RTN actual")).toHaveValue("Sin registrar");
    expect(within(dialog).queryByRole("checkbox", { name: "Quitar RTN" })).not.toBeInTheDocument();
    await user.click(within(dialog).getByRole("checkbox", { name: "Quitar documento" }));

    expect(onChange).toHaveBeenCalledWith("removeNationalId", "true");
    expect(onChange).toHaveBeenCalledWith("nationalId", "");
  });

  it("shows the error under its field, the summary, the alert with the trace id, and the wait", () => {
    renderWithAdminI18n(
      <GuardianForm
        {...props({
          fieldErrors: { rtn: "El RTN debe tener 14 dígitos; los guiones son opcionales." },
          form: formView({
            summary: [{ controlId: "guardian-rtn", message: "El RTN debe tener 14 dígitos; los guiones son opcionales." }],
            alert: { message: "No tiene permiso para realizar esta acción.", traceId: "t-9" },
            submitting: true,
            liveMessage: "Guardando los datos.",
          }),
        })}
      />,
    );

    expect(screen.getByLabelText(/^RTN/)).toHaveAccessibleDescription(expect.stringContaining("El RTN debe tener 14 dígitos"));
    expect(screen.getByText("Hay 1 campo con error.")).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("Identificador para soporte: t-9");
    expect(screen.getByText("Guardando los datos.")).toHaveAttribute("aria-live", "polite");
    expect(screen.getByRole("button", { name: "Cancelar" })).toBeDisabled();
  });
});
