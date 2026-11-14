import { screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { formView } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { initialExitValues } from "../enrollment-forms.ts";
import { ExitForm, type ExitFormProps } from "./ExitForm.tsx";

// States. AW57, AW58: the exit form rendered with props alone. It loads nothing. Loading: `form.submitting`, drawn by the
// dialog. Error: `fieldErrors` under each field. Empty: a new form with the kind on withdrawal and the date on today.
// Success: the reason is required for a withdrawal and for a transfer to another institution, and optional for a graduation.
function props(override: Partial<ExitFormProps> = {}): ExitFormProps {
  return {
    studentName: "Ana Pérez",
    values: initialExitValues(),
    fieldErrors: {},
    form: formView(),
    onChange: vi.fn(),
    onBlur: vi.fn(),
    onClose: vi.fn(),
    ...override,
  };
}

describe("ExitForm", () => {
  it("AW57: offers the three kinds, starts on a withdrawal, and requires the reason for it", () => {
    renderWithAdminI18n(<ExitForm {...props()} />);

    const dialog = screen.getByRole("dialog", { name: "Registrar salida" });
    expect(dialog).toHaveAccessibleDescription(expect.stringContaining("Ana Pérez"));
    const kind = within(dialog).getByLabelText(/Tipo de salida/);
    expect(within(kind).getAllByRole("option").map((option) => option.textContent)).toEqual(["Retiro", "Traslado a otra institución", "Graduación"]);
    expect(kind).toHaveValue("WITHDRAWN");
    expect(within(dialog).getByLabelText(/Motivo/)).toBeRequired();
    expect(within(dialog).getByLabelText(/Motivo/)).toHaveAccessibleDescription("Obligatorio. De 3 a 500 caracteres.");
    expect(within(dialog).getByLabelText(/Fecha de salida/)).toHaveAttribute("type", "date");
  });

  it("AW57: does not require the reason of a graduation, and says so", () => {
    renderWithAdminI18n(<ExitForm {...props({ values: { ...initialExitValues(), status: "GRADUATED" } })} />);

    expect(screen.getByLabelText("Motivo")).not.toBeRequired();
    expect(screen.getByLabelText("Motivo")).toHaveAccessibleDescription("Opcional para una graduación. De 3 a 500 caracteres.");
  });

  it("AW58: shows the errors under their fields and the pending request", () => {
    renderWithAdminI18n(
      <ExitForm
        {...props({
          fieldErrors: { withdrawnOn: "La fecha está fuera del rango permitido.", withdrawalReason: "Este dato es obligatorio." },
          form: formView({ submitting: true, liveMessage: "Registrando la salida." }),
        })}
      />,
    );

    expect(screen.getByLabelText(/Fecha de salida/)).toHaveAccessibleDescription("La fecha está fuera del rango permitido.");
    expect(screen.getByLabelText(/Motivo/)).toBeInvalid();
    expect(screen.getByText("Registrando la salida.")).toBeInTheDocument();
  });
});
