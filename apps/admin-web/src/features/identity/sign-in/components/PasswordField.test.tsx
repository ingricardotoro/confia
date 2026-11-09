import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { renderWithAdminI18n } from "../../../../test/render.tsx";
import { PasswordField } from "./PasswordField.tsx";

// admin-web-staff-sign-in, task 3.1 (E8; SS53, SS54). The password with its optional reveal.
// States. Success: hidden, shown, and with a field error. Loading, empty and error of a request: not applicable,
// it reads nothing and its value and error arrive from the form.
function Controlled({ error, onSubmit }: { error?: string; onSubmit?: () => void }) {
  const [value, setValue] = useState("");
  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        onSubmit?.();
      }}
    >
      <PasswordField
        label="Contraseña"
        controlId="password-under-test"
        value={value}
        onChange={setValue}
        onBlur={() => undefined}
        {...(error === undefined ? {} : { error })}
      />
    </form>
  );
}

const field = () => screen.getByLabelText(/Contraseña/, { selector: "input" });
const toggle = () => screen.getByRole("button", { name: "Mostrar contraseña" });

describe("PasswordField", () => {
  it("AW57, SS52: starts hidden, with the autocomplete of a current password, and marked as required in text", () => {
    renderWithAdminI18n(<Controlled />);

    expect(field()).toHaveAttribute("type", "password");
    expect(field()).toHaveAttribute("autocomplete", "current-password");
    expect(screen.getByText("Obligatorio")).toBeInTheDocument();
  });

  it("SS53: the control has the fixed name and aria-pressed, which follows the state", async () => {
    const user = userEvent.setup();
    renderWithAdminI18n(<Controlled />);
    expect(toggle()).toHaveAttribute("aria-pressed", "false");

    await user.click(toggle());

    expect(field()).toHaveAttribute("type", "text");
    expect(toggle()).toHaveAttribute("aria-pressed", "true");
    expect(screen.queryByRole("button", { name: /Ocultar/i })).not.toBeInTheDocument();

    await user.click(toggle());

    expect(field()).toHaveAttribute("type", "password");
    expect(toggle()).toHaveAttribute("aria-pressed", "false");
  });

  it("SS53: toggling keeps the value and leaves the focus on the control", async () => {
    const user = userEvent.setup();
    renderWithAdminI18n(<Controlled />);
    await user.type(field(), "CENTINELA-CLAVE");

    await user.click(toggle());

    expect(field()).toHaveValue("CENTINELA-CLAVE");
    expect(toggle()).toHaveFocus();

    await user.keyboard("{Enter}");

    expect(field()).toHaveAttribute("type", "password");
    expect(field()).toHaveValue("CENTINELA-CLAVE");
    expect(toggle()).toHaveFocus();
  });

  it("SS53: the control is not a submit button, so pressing it sends nothing", async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    renderWithAdminI18n(<Controlled onSubmit={onSubmit} />);

    await user.click(toggle());

    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("SS54: is hidden again in every mount, even after it was shown", async () => {
    const user = userEvent.setup();
    const first = renderWithAdminI18n(<Controlled />);
    await user.click(toggle());
    expect(field()).toHaveAttribute("type", "text");
    first.unmount();

    renderWithAdminI18n(<Controlled />);

    expect(field()).toHaveAttribute("type", "password");
    expect(toggle()).toHaveAttribute("aria-pressed", "false");
  });

  it("SS37: an error is marked on the field and described by its message", () => {
    renderWithAdminI18n(<Controlled error="Este dato es obligatorio." />);

    expect(field()).toHaveAttribute("aria-invalid", "true");
    expect(field()).toHaveAccessibleDescription("Este dato es obligatorio.");
  });
});
