import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { StatementStudentPicker, type StatementStudentPickerProps } from "./StatementStudentPicker.tsx";

// States. AW57, AW58: the student search rendered with props alone. Idle: the box with its help. Loading: the status line says it is
// searching. Error: a warning with a retry. Empty: the status line says nothing matched. Success: a listbox the arrows and Enter
// work, and the student chosen with the way to change it.
const ANA = { id: "00000000-0000-4000-8000-000000005001", studentCode: "E-000001", firstName: "Ana", lastName: "Aguilar" };
const SOFIA = { id: "00000000-0000-4000-8000-000000005003", studentCode: "E-000003", firstName: "Sofía", lastName: "Banegas" };

function props(override: Partial<StatementStudentPickerProps> = {}): StatementStudentPickerProps {
  return {
    controlId: "statement-student",
    draft: "",
    onDraftChange: vi.fn(),
    tooShort: false,
    search: "idle",
    results: [],
    onRetry: vi.fn(),
    chosenPending: false,
    onChoose: vi.fn(),
    onClear: vi.fn(),
    ...override,
  };
}

describe("StatementStudentPicker", () => {
  it("shows the box with its help and tells a single character is too short", () => {
    const { rerender } = renderWithAdminI18n(<StatementStudentPicker {...props()} />);
    expect(screen.getByText("Escriba al menos dos letras del nombre, del apellido o del código.")).toBeInTheDocument();

    rerender(<StatementStudentPicker {...props({ draft: "a", tooShort: true })} />);
    expect(screen.getByText("Escriba al menos dos caracteres para buscar.")).toBeInTheDocument();
  });

  it.each([
    ["waiting", "Buscando estudiantes."],
    ["loading", "Buscando estudiantes."],
  ] as const)("says it is searching while %s", (search, text) => {
    renderWithAdminI18n(<StatementStudentPicker {...props({ search })} />);

    expect(screen.getByRole("status")).toHaveTextContent(text);
  });

  it("says nothing matched, and offers a retry when the search failed", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    const { rerender } = renderWithAdminI18n(<StatementStudentPicker {...props({ search: "ready", draft: "zz" })} />);
    expect(screen.getByRole("status")).toHaveTextContent("Ningún estudiante coincide con la búsqueda.");

    rerender(<StatementStudentPicker {...props({ search: "failed", onRetry })} />);
    await user.click(screen.getByRole("button", { name: "Reintentar la búsqueda" }));
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it("moves through the results with the arrows and chooses with Enter, or with a click", async () => {
    const user = userEvent.setup();
    const onChoose = vi.fn();
    renderWithAdminI18n(<StatementStudentPicker {...props({ search: "ready", draft: "a", results: [ANA, SOFIA], onChoose })} />);

    expect(screen.getByRole("status")).toHaveTextContent("Se encontraron 2 estudiantes.");
    const box = screen.getByRole("combobox", { name: "Estudiante" });
    box.focus();
    await user.keyboard("{ArrowDown}{ArrowDown}{Enter}");
    expect(onChoose).toHaveBeenCalledWith(SOFIA);

    await user.click(screen.getByRole("option", { name: "Aguilar, Ana (E-000001)" }));
    expect(onChoose).toHaveBeenLastCalledWith(ANA);
  });

  it("shows the student chosen, loading until the statement names them, and clears the choice", async () => {
    const user = userEvent.setup();
    const onClear = vi.fn();
    const { rerender } = renderWithAdminI18n(<StatementStudentPicker {...props({ chosenPending: true, onClear })} />);
    expect(screen.getByText("Cargando al estudiante.")).toBeInTheDocument();

    rerender(<StatementStudentPicker {...props({ chosen: ANA, chosenPending: true, onClear })} />);
    expect(screen.getByText("Aguilar, Ana (E-000001)")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Cambiar estudiante" }));
    expect(onClear).toHaveBeenCalledOnce();
  });
});
