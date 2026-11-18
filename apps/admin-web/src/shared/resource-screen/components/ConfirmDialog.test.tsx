import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { ConfirmDialog } from "./ConfirmDialog.tsx";

// States. AW57, AW58: the confirmation of an action that changes a record for good, rendered with props alone: no Query provider,
// no router, no request. Loading: `busy` (the confirm button waits and the dialog does not close). Empty, error and
// success do not apply: it lists no data, and what the action answers is told by the screen that asked.
function renderDialog(override: Partial<Parameters<typeof ConfirmDialog>[0]> = {}) {
  const onConfirm = vi.fn();
  const onCancel = vi.fn();
  renderWithAdminI18n(
    <ConfirmDialog
      title="Activar el plan «Primaria 2027»"
      description="El plan queda congelado."
      confirmLabel="Activar plan"
      variant="success"
      busy={false}
      onConfirm={onConfirm}
      onCancel={onCancel}
      {...override}
    />,
  );
  return { onConfirm, onCancel };
}

describe("ConfirmDialog", () => {
  it("is a named and described modal and sends nothing until the confirm button is pressed", async () => {
    const user = userEvent.setup();
    const { onConfirm, onCancel } = renderDialog();

    const dialog = screen.getByRole("dialog", { name: "Activar el plan «Primaria 2027»" });
    expect(dialog).toHaveAccessibleDescription("El plan queda congelado.");
    expect(onConfirm).not.toHaveBeenCalled();
    await user.click(within(dialog).getByRole("button", { name: "Activar plan" }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onCancel).not.toHaveBeenCalled();
  });

  it("cancels with the cancel button and with Escape", async () => {
    const user = userEvent.setup();
    const { onConfirm, onCancel } = renderDialog();

    await user.click(screen.getByRole("button", { name: "Cancelar" }));
    await user.keyboard("{Escape}");

    expect(onCancel).toHaveBeenCalledTimes(2);
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it("while the action is in flight, does not close and the confirm button waits", async () => {
    const user = userEvent.setup();
    const { onConfirm, onCancel } = renderDialog({ busy: true });
    expect(screen.getByRole("button", { name: /Activar plan/ })).toHaveAttribute("aria-busy", "true");

    expect(screen.getByRole("button", { name: "Cancelar" })).toBeDisabled();
    await user.keyboard("{Escape}");
    await user.click(screen.getByRole("button", { name: /Activar plan/ }));

    expect(onCancel).not.toHaveBeenCalled();
    expect(onConfirm).not.toHaveBeenCalled();
  });
});
