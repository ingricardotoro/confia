import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { formView } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { initialTransferValues } from "../enrollment-forms.ts";
import { TransferForm, type TransferFormProps } from "./TransferForm.tsx";

// States. AW57, AW58: the transfer form rendered with props alone. Loading: `form.submitting`, drawn by the dialog, and
// `sectionsState` "loading". Error: `fieldErrors` under each field and the warning with a retry when the sections failed.
// Empty: a year with no other active section. Success: the groups of sections, the date and the optional reason.
function props(override: Partial<TransferFormProps> = {}): TransferFormProps {
  return {
    studentName: "Ana Pérez",
    values: initialTransferValues(),
    fieldErrors: {},
    sectionsState: "ready",
    sectionGroups: [{ label: "Primaria · Primero", options: [{ value: "b", label: "B" }] }],
    onRetrySections: vi.fn(),
    form: formView(),
    onChange: vi.fn(),
    onBlur: vi.fn(),
    onClose: vi.fn(),
    ...override,
  };
}

describe("TransferForm", () => {
  it("AW57: names the student in its description and asks for the new section, the date and an optional reason", () => {
    renderWithAdminI18n(<TransferForm {...props()} />);

    const dialog = screen.getByRole("dialog", { name: "Trasladar de sección" });
    expect(dialog).toHaveAccessibleDescription(expect.stringContaining("Ana Pérez"));
    expect(within(dialog).getByLabelText(/^Sección nueva/)).toBeRequired();
    expect(within(dialog).getByLabelText(/Fecha del traslado/)).toBeRequired();
    expect(within(dialog).getByLabelText("Motivo")).not.toBeRequired();
    expect(within(dialog).getByLabelText("Motivo")).toHaveAccessibleDescription("Opcional. De 3 a 500 caracteres.");
  });

  it("AW58: shows the errors under their fields and the pending request", () => {
    renderWithAdminI18n(
      <TransferForm
        {...props({
          fieldErrors: { toSectionId: "Elija una sección distinta de la actual.", reason: "Escriba un motivo de 3 a 500 caracteres." },
          form: formView({ submitting: true, liveMessage: "Trasladando la matrícula." }),
        })}
      />,
    );

    expect(screen.getByLabelText(/^Sección nueva/)).toHaveAccessibleDescription("Elija una sección distinta de la actual.");
    expect(screen.getByLabelText("Motivo")).toBeInvalid();
    expect(screen.getByText("Trasladando la matrícula.")).toBeInTheDocument();
  });

  it("AW58: says there is no other section, and offers a retry when the sections failed to load", async () => {
    const user = userEvent.setup();
    const onRetrySections = vi.fn();
    const { rerender } = renderWithAdminI18n(<TransferForm {...props({ sectionGroups: [] })} />);
    expect(screen.getByLabelText(/^Sección nueva/)).toHaveAccessibleDescription("El año lectivo no tiene otra sección activa a la que trasladar.");

    rerender(<TransferForm {...props({ sectionsState: "failed", sectionGroups: [], onRetrySections })} />);
    await user.click(screen.getByRole("button", { name: "Reintentar carga de secciones" }));

    expect(onRetrySections).toHaveBeenCalledOnce();
  });
});
