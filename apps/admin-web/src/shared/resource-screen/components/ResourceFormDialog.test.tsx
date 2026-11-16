import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createRef } from "react";
import { describe, expect, it, vi } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { ResourceFormDialog, type ResourceFormView } from "./ResourceFormDialog.tsx";

// States. AW57, AW58: the dialog of a form rendered with props alone: no Query provider, no router, no request.
// Loading: `submitting` (the button is busy, the wait is told in the polite region and the dialog does not
// close). Error: `summary` after a failed submit, `alert` for a failure the fields do not explain, and
// `countdown` after a 429. Empty and success do not apply: it lists no data, and a saved form closes.
function formView(override: Partial<ResourceFormView> = {}): ResourceFormView {
  return {
    summary: [],
    liveMessage: "",
    submitting: false,
    summaryRef: createRef<HTMLDivElement>(),
    alertRef: createRef<HTMLDivElement>(),
    onSubmit: vi.fn((event: { preventDefault: () => void }) => {
      event.preventDefault();
    }),
    ...override,
  };
}

function renderDialog(form: ResourceFormView, onClose = vi.fn()) {
  renderWithAdminI18n(
    <ResourceFormDialog title="Nuevo año lectivo" description="Complete los datos del año." submitLabel="Guardar" form={form} onClose={onClose}>
      <label>
        Nombre
        <input />
      </label>
    </ResourceFormDialog>,
  );
  return onClose;
}

describe("ResourceFormDialog", () => {
  it("AW57: is a named and described modal with the fields, a cancel and a submit button", async () => {
    const user = userEvent.setup();
    const form = formView();
    const onClose = renderDialog(form);

    const dialog = screen.getByRole("dialog", { name: "Nuevo año lectivo" });
    expect(dialog).toHaveAccessibleDescription("Complete los datos del año.");
    expect(within(dialog).getByLabelText("Nombre")).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Guardar" }));
    expect(form.onSubmit).toHaveBeenCalledTimes(1);
    await user.click(within(dialog).getByRole("button", { name: "Cancelar" }));

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("closes with Escape", async () => {
    const user = userEvent.setup();
    const onClose = renderDialog(formView());

    await user.keyboard("{Escape}");

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("the error state shows the summary with a link to each field, and the alert with the trace id", () => {
    renderDialog(
      formView({
        summary: [{ controlId: "missing", message: "Este dato es obligatorio." }],
        alert: { message: "No tiene permiso para realizar esta acción.", traceId: "trace-1" },
      }),
    );

    expect(screen.getByText("Hay 1 campo con error.")).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("No tiene permiso para realizar esta acción.");
    expect(screen.getByRole("alert")).toHaveTextContent("Identificador para soporte: trace-1");
  });

  it("shows the wait after a 429 outside every live region", () => {
    renderDialog(formView({ countdown: { remaining: "Puede intentar de nuevo en 30 s." } }));

    expect(screen.getByText("Puede intentar de nuevo en 30 s.").closest("[aria-live]")).toBeNull();
  });

  it("the loading state marks the submit button busy, tells the wait politely, and does not close", async () => {
    const user = userEvent.setup();
    const form = formView({ submitting: true, liveMessage: "Guardando los datos." });
    const onClose = renderDialog(form);

    const submit = screen.getByRole("button", { name: /Guardar/ });
    expect(submit).toHaveAttribute("aria-busy", "true");
    expect(screen.getByText("Guardando los datos.")).toHaveAttribute("aria-live", "polite");
    expect(screen.getByRole("button", { name: "Cancelar" })).toBeDisabled();
    await user.keyboard("{Escape}");
    await user.click(submit);

    expect(onClose).not.toHaveBeenCalled();
    expect(form.onSubmit).not.toHaveBeenCalled();
  });
});
