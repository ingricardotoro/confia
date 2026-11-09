import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { CopyButton } from "./CopyButton.tsx";

// admin-web-staff-sign-in, task 2.2 (AW54). A button that copies a text and says, in a polite region, whether it did.
// States. Success: the text was copied and announced. Error: the clipboard refused and the person is told to copy by
// hand. Loading and empty: not applicable, it reads nothing and a text is always given.
describe("CopyButton", () => {
  function setup(props: { text: string; kind: "key" | "trace" }) {
    const user = userEvent.setup();
    const write = vi.spyOn(navigator.clipboard, "writeText");
    renderWithAdminI18n(<CopyButton {...props} />);
    return { user, write };
  }

  it("AW57: names the control by what it copies", () => {
    setup({ text: "ABC", kind: "trace" });
    expect(screen.getByRole("button", { name: "Copiar identificador" })).toBeInTheDocument();
  });

  it("names the control of a key as the key", () => {
    setup({ text: "ABC", kind: "key" });
    expect(screen.getByRole("button", { name: "Copiar clave" })).toBeInTheDocument();
  });

  it("copies the text without spaces and announces it politely", async () => {
    const { user, write } = setup({ text: "JBSW Y3DP EHPK", kind: "key" });

    await user.click(screen.getByRole("button", { name: "Copiar clave" }));

    expect(write).toHaveBeenCalledExactlyOnceWith("JBSWY3DPEHPK");
    expect(await screen.findByText("Clave copiada.")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Clave copiada.");
  });

  it("announces that a trace id was copied", async () => {
    const { user } = setup({ text: "01J8Z4K2QF9P0X", kind: "trace" });

    await user.click(screen.getByRole("button", { name: "Copiar identificador" }));

    expect(await screen.findByText("Identificador copiado.")).toBeInTheDocument();
  });

  it("says so, and not that it copied, when the clipboard refuses", async () => {
    const { user, write } = setup({ text: "ABC", kind: "trace" });
    write.mockRejectedValueOnce(new Error("denied"));

    await user.click(screen.getByRole("button", { name: "Copiar identificador" }));

    expect(await screen.findByText("No se pudo copiar. Seleccione el texto y cópielo manualmente.")).toBeInTheDocument();
    expect(screen.queryByText("Identificador copiado.")).not.toBeInTheDocument();
  });

  it("says so, and does not throw, when writing to the clipboard throws at once", async () => {
    const { user, write } = setup({ text: "ABC", kind: "trace" });
    write.mockImplementationOnce(() => {
      throw new TypeError("not allowed");
    });

    await user.click(screen.getByRole("button", { name: "Copiar identificador" }));

    expect(await screen.findByText("No se pudo copiar. Seleccione el texto y cópielo manualmente.")).toBeInTheDocument();
  });

  it("says so when there is no clipboard, as outside a secure context", async () => {
    const user = userEvent.setup();
    const original = Object.getOwnPropertyDescriptor(navigator, "clipboard");
    Object.defineProperty(navigator, "clipboard", { value: undefined, configurable: true });
    try {
      renderWithAdminI18n(<CopyButton text="ABC" kind="trace" />);

      await user.click(screen.getByRole("button", { name: "Copiar identificador" }));

      expect(await screen.findByText("No se pudo copiar. Seleccione el texto y cópielo manualmente.")).toBeInTheDocument();
    } finally {
      // Put back what was there, or remove the own property this test added, so later tests see the clipboard again.
      if (original) Object.defineProperty(navigator, "clipboard", original);
      else Reflect.deleteProperty(navigator, "clipboard");
    }
  });

  it("has an empty polite region before anything is copied", () => {
    setup({ text: "ABC", kind: "trace" });
    expect(screen.getByRole("status")).toBeEmptyDOMElement();
  });
});
