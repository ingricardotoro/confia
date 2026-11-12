import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { SectionField, type SectionFieldProps } from "./SectionField.tsx";

// States. AW57, AW58: the select of sections rendered with props alone. It loads nothing: loading and failure are said by the
// form around it. Success: the groups. Empty: no groups, only the invitation to choose. Error: the message under the field.
function props(override: Partial<SectionFieldProps> = {}): SectionFieldProps {
  return {
    label: "Sección",
    controlId: "section",
    value: "",
    groups: [
      { label: "Primaria · Primero", options: [{ value: "a", label: "A" }, { value: "b", label: "B" }] },
      { label: "Secundaria · Séptimo", options: [{ value: "c", label: "A" }] },
    ],
    onChange: vi.fn(),
    onBlur: vi.fn(),
    ...override,
  };
}

describe("SectionField", () => {
  it("AW57: groups the sections by modality and grade, and reports the one chosen", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithAdminI18n(<SectionField {...props({ onChange })} />);

    expect(screen.getByRole("group", { name: "Primaria · Primero" })).toBeInTheDocument();
    expect(within(screen.getByRole("group", { name: "Secundaria · Séptimo" })).getByRole("option", { name: "A" })).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText("Sección"), "b");

    expect(onChange).toHaveBeenCalledWith("b");
  });

  it("AW58: with no groups only the invitation remains, and an error is tied to the field", () => {
    renderWithAdminI18n(<SectionField {...props({ groups: [], error: "Este dato es obligatorio.", required: true })} />);

    expect(screen.getAllByRole("option")).toHaveLength(1);
    expect(screen.getByLabelText(/Sección/)).toHaveAccessibleDescription("Este dato es obligatorio.");
    expect(screen.getByLabelText(/Sección/)).toBeInvalid();
  });
});
