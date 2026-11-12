import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import type { StudentChoice } from "../enrollment-model.ts";
import { StudentPicker, type StudentPickerProps } from "./StudentPicker.tsx";

// States. AW57, AW58: the student picker rendered with props alone. Idle: the help. Loading (waiting and loading): the status
// line says it is searching. Error: the warning with a retry. Empty: a status line says none was found and no listbox is drawn.
// Success: the listbox with its options and the student chosen with a button to change it.
const ANA: StudentChoice = { id: "a", studentCode: "E-000001", firstName: "Ana", lastName: "Pérez" };
const LUZ: StudentChoice = { id: "l", studentCode: "E-000009", firstName: "Luz", lastName: "Pérez" };

function props(override: Partial<StudentPickerProps> = {}): StudentPickerProps {
  return {
    controlId: "student",
    draft: "",
    onDraftChange: vi.fn(),
    tooShort: false,
    search: "idle",
    results: [],
    onRetry: vi.fn(),
    onChoose: vi.fn(),
    onClear: vi.fn(),
    ...override,
  };
}

describe("StudentPicker", () => {
  it("AW57: is a combobox whose results are a listbox of options, moved with the arrows and chosen with Enter", async () => {
    const user = userEvent.setup();
    const onChoose = vi.fn();
    renderWithAdminI18n(<StudentPicker {...props({ draft: "Pér", search: "ready", results: [ANA, LUZ], onChoose })} />);
    const box = screen.getByRole("combobox", { name: /Buscar estudiante/ });

    expect(box).toHaveAttribute("aria-expanded", "true");
    expect(box).toHaveAttribute("aria-controls", screen.getByRole("listbox", { name: "Estudiantes encontrados" }).id);
    expect(screen.getByRole("status")).toHaveTextContent("Se encontraron 2 estudiantes.");
    box.focus();
    await user.keyboard("{ArrowDown}{ArrowDown}");
    expect(screen.getByRole("option", { name: "Luz Pérez · E-000009" })).toHaveAttribute("aria-selected", "true");
    expect(box).toHaveAttribute("aria-activedescendant", screen.getByRole("option", { name: "Luz Pérez · E-000009" }).id);
    await user.keyboard("{Enter}");

    expect(onChoose).toHaveBeenCalledWith(LUZ);
  });

  it("AW57: chooses with the pointer without taking the focus from the box", async () => {
    const user = userEvent.setup();
    const onChoose = vi.fn();
    renderWithAdminI18n(<StudentPicker {...props({ draft: "Pér", search: "ready", results: [ANA], onChoose })} />);
    screen.getByRole("combobox").focus();

    await user.click(screen.getByRole("option", { name: "Ana Pérez · E-000001" }));

    expect(onChoose).toHaveBeenCalledWith(ANA);
  });

  it("AW57: is closed to spell check, autocorrect and autofill, and says that one character is too few", () => {
    renderWithAdminI18n(<StudentPicker {...props({ draft: "P", tooShort: true })} />);

    const box = screen.getByRole("combobox");
    expect(box).toHaveAttribute("spellcheck", "false");
    expect(box).toHaveAttribute("autocomplete", "off");
    expect(box).toHaveAccessibleDescription("Escriba al menos 2 caracteres para buscar.");
    expect(box).toHaveAttribute("aria-expanded", "false");
    expect(box).not.toHaveAttribute("aria-controls");
  });

  it.each(["waiting", "loading"] as const)("AW58: says it is searching while %s, with no listbox", (search) => {
    renderWithAdminI18n(<StudentPicker {...props({ draft: "Pér", search })} />);

    expect(screen.getByRole("status")).toHaveTextContent("Buscando estudiantes.");
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
  });

  it("AW58: says when there is none, and warns of a failed search with a retry", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    const { rerender } = renderWithAdminI18n(<StudentPicker {...props({ draft: "zz", search: "ready" })} />);
    expect(screen.getByRole("status")).toHaveTextContent("No se encontró ningún estudiante activo con ese dato.");
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();

    rerender(<StudentPicker {...props({ draft: "zz", search: "failed", onRetry })} />);
    await user.click(screen.getByRole("button", { name: "Reintentar búsqueda" }));

    expect(onRetry).toHaveBeenCalledOnce();
  });

  it("AW58: shows an error under the box, tied to it", () => {
    renderWithAdminI18n(<StudentPicker {...props({ error: "Este dato es obligatorio." })} />);

    expect(screen.getByRole("combobox")).toBeInvalid();
    expect(screen.getByRole("combobox")).toHaveAccessibleDescription(expect.stringContaining("Este dato es obligatorio."));
  });

  it("AW57: shows the student chosen in place of the box, with a button that clears the choice", async () => {
    const user = userEvent.setup();
    const onClear = vi.fn();
    renderWithAdminI18n(<StudentPicker {...props({ chosen: ANA, onClear })} />);

    expect(screen.getByText("Ana Pérez · E-000001")).toBeInTheDocument();
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Cambiar estudiante" }));

    expect(onClear).toHaveBeenCalledOnce();
  });
});
