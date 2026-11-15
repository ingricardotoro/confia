import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { renderWithAdminI18n } from "../../../../test/render.tsx";
import { RecoveryCodesStep, type RecoveryCodesStepProps } from "./RecoveryCodesStep.tsx";

// admin-web-staff-sign-in, task 8.1 (E10; SS89, SS95). The recovery codes step as a presentation.
// States. Success: the codes with the confirmation unchecked and checked. Error: Continue pressed without the confirmation,
// shown here from props as the summary and the message of the checkbox. Loading and empty: not applicable, the container
// renders this step only once the codes arrived (AW58), and a step without codes does not exist.
const noop = () => undefined;
// Made at run time: the file holds no code.
const CODES = Array.from({ length: 10 }, () => crypto.randomUUID().replaceAll("-", "").slice(0, 10));
const base: RecoveryCodesStepProps = {
  codes: CODES,
  confirmed: false,
  summary: [],
  summaryRef: noop,
  onConfirmedChange: noop,
  onContinue: noop,
};

function show(props: Partial<RecoveryCodesStepProps> = {}) {
  return renderWithAdminI18n(<RecoveryCodesStep {...base} {...props} />);
}

const checkbox = () => screen.getByRole("checkbox", { name: /He guardado mis códigos de recuperación/ });

describe("RecoveryCodesStep", () => {
  it("AW57, SS89: shows the ten codes as the text of ten list items, not as images or fields", () => {
    show();

    const items = within(screen.getByRole("list")).getAllByRole("listitem");
    expect(items.map((item) => item.textContent)).toEqual(CODES);
    expect(screen.queryByRole("img")).not.toBeInTheDocument();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
  });

  it("has its heading, the intro that says they are not shown again, and takes the focus on the heading", async () => {
    show();

    expect(screen.getByText("Cada código sirve una sola vez si pierde acceso a su aplicación. No se volverán a mostrar.")).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByRole("heading", { level: 2, name: "Guarde sus códigos de recuperación" })).toHaveFocus();
    });
  });

  it("SS95: offers no way to download, print or send the codes: the only controls are the checkbox and Continue", () => {
    show();

    expect(screen.getAllByRole("button").map((button) => button.textContent)).toEqual(["Continuar"]);
    expect(screen.getAllByRole("checkbox")).toHaveLength(1);
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
    expect(screen.queryByText(/descargar|imprimir|correo|enviar/i)).not.toBeInTheDocument();
  });

  it("has the checkbox unchecked and reports the change", async () => {
    const user = userEvent.setup();
    const onConfirmedChange = vi.fn();
    show({ onConfirmedChange });

    expect(checkbox()).not.toBeChecked();
    await user.click(checkbox());

    expect(onConfirmedChange).toHaveBeenCalledExactlyOnceWith(true);
  });

  it("shows the checkbox checked when it is confirmed", () => {
    show({ confirmed: true });

    expect(checkbox()).toBeChecked();
  });

  it("SS90: Continue is never disabled, with or without the confirmation, and reports the press", async () => {
    const user = userEvent.setup();
    const onContinue = vi.fn();
    show({ onContinue });

    expect(screen.getByRole("button", { name: "Continuar" })).toBeEnabled();
    await user.click(screen.getByRole("button", { name: "Continuar" }));

    expect(onContinue).toHaveBeenCalledOnce();
  });

  it("SS90: shows the summary with a link to the checkbox and the message under it", async () => {
    const user = userEvent.setup();
    const message = "Confirme que guardó sus códigos de recuperación para continuar.";
    show({ summary: [{ controlId: "enrollment-codes-confirm", message }], error: message });

    const summary = screen.getByRole("group", { name: "Hay 1 campo con error." });
    await user.click(within(summary).getByRole("link", { name: message }));

    expect(checkbox()).toHaveFocus();
    expect(checkbox()).toHaveAttribute("aria-invalid", "true");
    expect(checkbox()).toHaveAccessibleDescription(message);
  });
});
