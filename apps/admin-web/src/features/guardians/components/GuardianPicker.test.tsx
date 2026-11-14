import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { GuardianPicker, type GuardianPickerProps } from "./GuardianPicker.tsx";

const ROSA = { id: "g1", guardianCode: "P-000001", firstName: "Rosa", lastName: "Mejía", status: "ACTIVE" as const };
const LUIS = { id: "g2", guardianCode: "P-000002", firstName: "Luis", lastName: "Paz", status: "ACTIVE" as const };

function props(override: Partial<GuardianPickerProps> = {}): GuardianPickerProps {
  return { controlId: "pick", draft: "", onDraftChange: vi.fn(), tooShort: false, full: false, search: "idle", results: [], onRetry: vi.fn(), onChoose: vi.fn(), ...override };
}

// States. AW57, AW58: the search that adds a guardian: idle, too short, searching, failed, no results, results, and full.
describe("GuardianPicker", () => {
  it("is a combobox with the browser helpers off, and says what to type", () => {
    renderWithAdminI18n(<GuardianPicker {...props()} />);

    const box = screen.getByRole("combobox", { name: "Agregar un encargado" });
    expect(box).toHaveAttribute("autocomplete", "off");
    expect(box).toHaveAttribute("spellcheck", "false");
    expect(box).toHaveAccessibleDescription(/Escriba al menos 2 caracteres/);
  });

  it("says that one character is too few", () => {
    renderWithAdminI18n(<GuardianPicker {...props({ draft: "r", tooShort: true })} />);

    expect(screen.getByRole("combobox")).toHaveAccessibleDescription("Escriba al menos 2 caracteres para buscar.");
  });

  it.each([
    ["waiting", "Buscando encargados."],
    ["loading", "Buscando encargados."],
  ] as const)("says it is searching while %s", (search, text) => {
    renderWithAdminI18n(<GuardianPicker {...props({ draft: "ro", search })} />);

    expect(screen.getByRole("status")).toHaveTextContent(text);
  });

  it("says nobody was found", () => {
    renderWithAdminI18n(<GuardianPicker {...props({ draft: "zz", search: "ready" })} />);

    expect(screen.getByRole("status")).toHaveTextContent("No se encontró ningún encargado activo");
  });

  it("offers a retry when the search failed", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderWithAdminI18n(<GuardianPicker {...props({ draft: "ro", search: "failed", onRetry })} />);

    await user.click(screen.getByRole("button", { name: "Reintentar búsqueda" }));

    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it("lists the results, moves with the arrows, and chooses with Enter or the pointer", async () => {
    const user = userEvent.setup();
    const onChoose = vi.fn();
    renderWithAdminI18n(<GuardianPicker {...props({ draft: "me", search: "ready", results: [ROSA, LUIS], onChoose })} />);
    const box = screen.getByRole("combobox");

    expect(box).toHaveAttribute("aria-expanded", "true");
    await user.click(box);
    await user.keyboard("{ArrowDown}{ArrowDown}");
    expect(box).toHaveAttribute("aria-activedescendant", screen.getByRole("option", { name: "Luis Paz · P-000002" }).id);
    await user.keyboard("{Enter}");
    await user.click(screen.getByRole("option", { name: "Rosa Mejía · P-000001" }));

    expect(onChoose).toHaveBeenNthCalledWith(1, LUIS);
    expect(onChoose).toHaveBeenNthCalledWith(2, ROSA);
  });

  it("replaces the box with the reason when the set is full", () => {
    renderWithAdminI18n(<GuardianPicker {...props({ full: true })} />);

    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
    expect(screen.getByText("Se alcanzó el máximo de 20 encargados por estudiante.")).toBeInTheDocument();
  });
});
