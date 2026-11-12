import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { ReasonField, type ReasonFieldProps } from "./ReasonField.tsx";

// States. AW57, AW58: the reason field rendered with props alone. It loads nothing. Success: a labelled text area with its help.
// Error: the message tied to the field. Empty: a new field is empty. Its browser help (spell check, autofill) is off.
function props(override: Partial<ReasonFieldProps> = {}): ReasonFieldProps {
  return { label: "Motivo", controlId: "reason", value: "", description: "Opcional.", onChange: vi.fn(), onBlur: vi.fn(), ...override };
}

describe("ReasonField", () => {
  it("AW57: is labelled, described, limited to 500 characters and closed to spell check and autofill", () => {
    renderWithAdminI18n(<ReasonField {...props()} />);

    const field = screen.getByLabelText("Motivo");
    expect(field).toHaveAccessibleDescription("Opcional.");
    expect(field).toHaveAttribute("maxlength", "500");
    expect(field).toHaveAttribute("spellcheck", "false");
    expect(field).toHaveAttribute("autocomplete", "off");
    expect(field).toHaveValue("");
  });

  it("AW58: reports what is typed and shows an error in text, tied to the field", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithAdminI18n(<ReasonField {...props({ onChange, error: "Escriba un motivo de 3 a 500 caracteres.", required: true })} />);

    await user.type(screen.getByLabelText(/Motivo/), "a");

    expect(onChange).toHaveBeenCalledWith("a");
    expect(screen.getByLabelText(/Motivo/)).toBeInvalid();
    expect(screen.getByLabelText(/Motivo/)).toHaveAccessibleDescription(expect.stringContaining("Escriba un motivo de 3 a 500 caracteres."));
  });
});
