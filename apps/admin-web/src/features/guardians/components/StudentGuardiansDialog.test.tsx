import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createRef } from "react";
import { describe, expect, it, vi } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { draftOf, type LinkDraft } from "../link-model.ts";
import type { LinksFormView } from "../use-links-form.ts";
import { StudentGuardiansDialog, type StudentGuardiansDialogProps, type StudentGuardiansState } from "./StudentGuardiansDialog.tsx";

const GUARDIAN = { id: "g1", guardianCode: "P-000001", firstName: "Rosa", lastName: "Mejía", status: "ACTIVE" as const };
const row = (override: Partial<LinkDraft> = {}): LinkDraft => ({ ...draftOf(GUARDIAN), relationship: "MOTHER", percent: "60.00", primary: true, ...override });

function form(override: Partial<LinksFormView> = {}): LinksFormView {
  return {
    rows: [row()],
    rowErrors: {},
    summary: [],
    liveMessage: "",
    submitting: false,
    full: false,
    attachSummary: createRef<HTMLDivElement>(),
    attachAlert: createRef<HTMLDivElement>(),
    add: vi.fn(),
    remove: vi.fn(),
    change: vi.fn(),
    onSubmit: vi.fn((event: { preventDefault: () => void }) => {
      event.preventDefault();
    }),
    ...override,
  };
}

const picker = { controlId: "pick", draft: "", onDraftChange: vi.fn(), tooShort: false, full: false, search: "idle", results: [], onRetry: vi.fn(), onChoose: vi.fn() } as const;

function ready(override: Partial<Extract<StudentGuardiansState, { kind: "ready" }>> = {}): StudentGuardiansState {
  return { kind: "ready", savedTotal: "100.00", hadLinks: true, form: form(), picker, ...override };
}

function props(state: StudentGuardiansState, override: Partial<StudentGuardiansDialogProps> = {}): StudentGuardiansDialogProps {
  return { studentName: "Ana Pérez", state, onChange: vi.fn(), onRemove: vi.fn(), onClose: vi.fn(), ...override };
}

// States. AW57, AW58: the dialog with props alone: loading (a skeleton and no save button), failed (a retry), empty set, rows with
// errors, an alert, and the wait while saving. The sum is a string the container formatted from the server's number.
describe("StudentGuardiansDialog", () => {
  it("names the dialog for the student and shows a skeleton with a text for the reader while loading, without a save button", () => {
    renderWithAdminI18n(<StudentGuardiansDialog {...props({ kind: "loading" })} />);

    expect(screen.getByRole("dialog", { name: "Encargados de Ana Pérez" })).toBeInTheDocument();
    expect(screen.getByText("Cargando los encargados del estudiante.")).toHaveClass("sr-only");
    expect(screen.queryByRole("button", { name: "Guardar encargados" })).not.toBeInTheDocument();
  });

  it("offers a retry when it failed", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderWithAdminI18n(<StudentGuardiansDialog {...props({ kind: "failed", description: "Falló.", onRetry })} />);

    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it("shows the rule, the sum given by the server, and the row with its controls named for the guardian", () => {
    renderWithAdminI18n(<StudentGuardiansDialog {...props(ready({ savedTotal: "99.99" }))} />);

    expect(screen.getByText(/deben sumar exactamente 100\.00/)).toBeInTheDocument();
    expect(screen.getByText("Suma guardada actualmente (la calcula el servidor): 99.99 %")).toBeInTheDocument();
    const group = screen.getByRole("group", { name: "Rosa Mejía · P-000001" });
    expect(within(group).getByLabelText(/Porcentaje/)).toHaveClass("text-right", "tabular-nums");
    expect(within(group).getByRole("button", { name: "Quitar a Rosa Mejía del reparto" })).toBeInTheDocument();
  });

  it("calls back with the row key for each change and for the removal", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    const onRemove = vi.fn();
    const only = row();
    renderWithAdminI18n(<StudentGuardiansDialog {...props(ready({ form: form({ rows: [only] }) }), { onChange, onRemove })} />);

    await user.click(screen.getByRole("checkbox", { name: "Destinatario de la factura" }));
    await user.selectOptions(screen.getByLabelText(/Parentesco/), "FATHER");
    await user.click(screen.getByRole("button", { name: "Quitar a Rosa Mejía del reparto" }));

    expect(onChange).toHaveBeenCalledWith(only.key, { billing: true });
    expect(onChange).toHaveBeenCalledWith(only.key, { relationship: "FATHER" });
    expect(onRemove).toHaveBeenCalledWith(only.key);
  });

  it("shows the errors of a row under its fields and the summary of the form", () => {
    const only = row({ percent: "101" });
    renderWithAdminI18n(
      <StudentGuardiansDialog
        {...props(
          ready({
            form: form({
              rows: [only],
              rowErrors: { [only.key]: { percent: "El porcentaje debe estar entre 0.01 y 100." } },
              summary: [{ controlId: `link-percent-${only.key}`, message: "El porcentaje debe estar entre 0.01 y 100." }],
            }),
          }),
        )}
      />,
    );

    expect(screen.getByLabelText(/Porcentaje/)).toHaveAccessibleDescription(expect.stringContaining("entre 0.01 y 100"));
    expect(screen.getByText("Hay 1 campo con error.")).toBeInTheDocument();
  });

  it("shows the alert of a refusal with the trace id", () => {
    renderWithAdminI18n(<StudentGuardiansDialog {...props(ready({ form: form({ alert: { message: "Los porcentajes deben sumar exactamente 100.00.", traceId: "t-1" } }) }))} />);

    expect(screen.getByRole("alert")).toHaveTextContent("Los porcentajes deben sumar exactamente 100.00.");
    expect(screen.getByRole("alert")).toHaveTextContent("Identificador para soporte: t-1");
  });

  it("says how to start for an empty set, and warns that saving it removes the links the student has", () => {
    const { rerender } = renderWithAdminI18n(<StudentGuardiansDialog {...props(ready({ hadLinks: false, form: form({ rows: [] }) }))} />);
    expect(screen.getByText("Este estudiante no tiene encargados. Agregue uno con el buscador.")).toBeInTheDocument();
    expect(screen.queryByText(/se quitarán todos los vínculos/)).not.toBeInTheDocument();

    rerender(<StudentGuardiansDialog {...props(ready({ hadLinks: true, form: form({ rows: [] }) }))} />);

    expect(screen.getByText(/se quitarán todos los vínculos vigentes/)).toBeInTheDocument();
  });

  it("blocks cancel and shows the wait while it saves, and announces it in the polite region", () => {
    renderWithAdminI18n(<StudentGuardiansDialog {...props(ready({ form: form({ submitting: true, liveMessage: "Guardando los encargados." }) }))} />);

    expect(screen.getByRole("button", { name: "Cancelar" })).toBeDisabled();
    expect(screen.getByText("Guardando los encargados.")).toHaveAttribute("aria-live", "polite");
  });
});
