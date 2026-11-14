import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { Button } from "../../atoms/button";
import { Input } from "../../atoms/input";
import { renderWithI18n } from "../../test/render";
import { FormField } from "../form-field";
import { Dialog } from "./Dialog";

function Harness({ onOpenChange }: { onOpenChange?: (open: boolean) => void }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button
        onClick={() => {
          setOpen(true);
        }}
      >
        Abrir
      </Button>
      <Dialog
        open={open}
        onOpenChange={(next) => {
          setOpen(next);
          onOpenChange?.(next);
        }}
        title="Nueva modalidad"
        description="Registre el nombre."
        footer={<Button>Guardar</Button>}
      >
        <FormField label="Nombre">{(control) => <Input {...control} />}</FormField>
      </Dialog>
    </>
  );
}

describe("Dialog", () => {
  it("is not in the page while closed", () => {
    renderWithI18n(<Harness />);

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("opens as a dialog named by its title and described by its description, focusing the first field", async () => {
    const user = userEvent.setup();
    renderWithI18n(<Harness />);

    await user.click(screen.getByRole("button", { name: "Abrir" }));

    const dialog = await screen.findByRole("dialog", { name: "Nueva modalidad", description: "Registre el nombre." });
    expect(dialog).toBeVisible();
    expect(screen.getByRole("textbox", { name: "Nombre" })).toHaveFocus();
  });

  it("keeps the focus inside while tabbing and closes with Escape, returning the focus to the opener", async () => {
    const user = userEvent.setup();
    const onOpenChange = vi.fn();
    renderWithI18n(<Harness onOpenChange={onOpenChange} />);
    await user.click(screen.getByRole("button", { name: "Abrir" }));
    const dialog = await screen.findByRole("dialog");

    for (let step = 0; step < 6; step += 1) {
      await user.tab();
      expect(dialog).toContainElement(document.activeElement as HTMLElement);
    }

    await user.keyboard("{Escape}");
    expect(onOpenChange).toHaveBeenLastCalledWith(false);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Abrir" })).toHaveFocus();
    });
  });

  it("closes from the labelled close button", async () => {
    const user = userEvent.setup();
    renderWithI18n(<Harness />);
    await user.click(screen.getByRole("button", { name: "Abrir" }));

    await user.click(await screen.findByRole("button", { name: "Cerrar" }));

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
});
