import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { formView } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { initialEnrollValues } from "../enrollment-forms.ts";
import { EnrollForm, type EnrollFormProps } from "./EnrollForm.tsx";

// States. AW57, AW58: the enroll form rendered with props alone. Loading: `form.submitting`, drawn by the dialog, and
// `sectionsState` "loading". Error: `fieldErrors` under each field, the warning with a retry when the sections failed, and
// the warning that no year is in progress. Empty: a year with no active section. Success: the groups of sections.
function props(override: Partial<EnrollFormProps> = {}): EnrollFormProps {
  return {
    values: initialEnrollValues(),
    fieldErrors: {},
    picker: { draft: "", onDraftChange: vi.fn(), tooShort: false, search: "idle", results: [], onRetry: vi.fn(), onChoose: vi.fn(), onClear: vi.fn() },
    sectionsState: "ready",
    sectionGroups: [{ label: "Primaria · Primero", options: [{ value: "a", label: "A" }] }],
    onRetrySections: vi.fn(),
    form: formView(),
    onChange: vi.fn(),
    onBlur: vi.fn(),
    onClose: vi.fn(),
    ...override,
  };
}

describe("EnrollForm", () => {
  it("AW57: asks for the student, the section and the date, all required, with the date starting on today", () => {
    renderWithAdminI18n(<EnrollForm {...props()} />);

    const dialog = screen.getByRole("dialog", { name: "Matricular estudiante" });
    expect(within(dialog).getByRole("combobox", { name: /Buscar estudiante/ })).toBeRequired();
    expect(within(dialog).getByLabelText(/^Sección/)).toBeRequired();
    expect(within(dialog).getByLabelText(/Fecha de matrícula/)).toBeRequired();
    expect(within(dialog).getByLabelText(/Fecha de matrícula/)).toHaveAttribute("type", "date");
    expect(within(dialog).getByRole("group", { name: "Primaria · Primero" })).toBeInTheDocument();
    expect(within(dialog).queryByLabelText(/Año lectivo/)).not.toBeInTheDocument();
  });

  it("AW58: shows the errors under their fields, and the pending request in the submit button", () => {
    renderWithAdminI18n(
      <EnrollForm
        {...props({
          fieldErrors: { studentId: "Este dato es obligatorio.", sectionId: "Este dato es obligatorio.", enrolledOn: "Fecha mala." },
          form: formView({ submitting: true, liveMessage: "Matriculando al estudiante." }),
        })}
      />,
    );

    expect(screen.getByLabelText(/^Sección/)).toBeInvalid();
    expect(screen.getByLabelText(/Fecha de matrícula/)).toHaveAccessibleDescription("Fecha mala.");
    expect(screen.getByText("Matriculando al estudiante.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Matricular/ })).toHaveAttribute("aria-busy", "true");
  });

  it("AW58: says that no year is in progress, that the sections are loading, that there are none, and offers a retry when they failed", async () => {
    const user = userEvent.setup();
    const onRetrySections = vi.fn();
    const { rerender } = renderWithAdminI18n(<EnrollForm {...props({ sectionsState: "no-year", sectionGroups: [] })} />);
    expect(screen.getByText(/No hay un año lectivo en curso/)).toBeInTheDocument();

    rerender(<EnrollForm {...props({ sectionsState: "loading", sectionGroups: [] })} />);
    expect(screen.getByLabelText(/^Sección/)).toHaveAccessibleDescription("Cargando las secciones del año lectivo en curso.");

    rerender(<EnrollForm {...props({ sectionsState: "ready", sectionGroups: [] })} />);
    expect(screen.getByLabelText(/^Sección/)).toHaveAccessibleDescription("El año lectivo en curso no tiene secciones activas.");

    rerender(<EnrollForm {...props({ sectionsState: "failed", sectionGroups: [], onRetrySections })} />);
    await user.click(screen.getByRole("button", { name: "Reintentar carga de secciones" }));
    expect(onRetrySections).toHaveBeenCalledOnce();
  });
});
