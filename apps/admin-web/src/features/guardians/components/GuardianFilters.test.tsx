import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { GuardianFilters, type GuardianFiltersProps } from "./GuardianFilters.tsx";

// States. AW57, AW58: the filters are controls with props alone, so there is no loading, empty or error state of their own (the
// list that they filter has them). Their states are: the hint of the search (what it looks in, or that two characters are
// needed), the value of the box and of the status, and the callbacks.
function props(override: Partial<GuardianFiltersProps> = {}): GuardianFiltersProps {
  return { search: { value: "", onChange: vi.fn(), tooShort: false }, status: { value: "", onChange: vi.fn() }, ...override };
}

describe("GuardianFilters", () => {
  it("is a search box with the browser helpers off, that says what it looks in", () => {
    renderWithAdminI18n(<GuardianFilters {...props()} />);

    const box = screen.getByRole("searchbox", { name: "Buscar" });
    expect(box).toHaveAttribute("autocomplete", "off");
    expect(box).toHaveAttribute("spellcheck", "false");
    expect(box).toHaveAccessibleDescription("Por nombre, apellido o código.");
  });

  it("says that two characters are needed when one is typed", () => {
    renderWithAdminI18n(<GuardianFilters {...props({ search: { value: "r", onChange: vi.fn(), tooShort: true } })} />);

    expect(screen.getByRole("searchbox")).toHaveAccessibleDescription("Escriba al menos 2 caracteres para buscar.");
  });

  it("reports what is typed and the state chosen, and an empty choice as all", async () => {
    const user = userEvent.setup();
    const onSearch = vi.fn();
    const onStatus = vi.fn();
    renderWithAdminI18n(<GuardianFilters {...props({ search: { value: "", onChange: onSearch, tooShort: false }, status: { value: "ACTIVE", onChange: onStatus } })} />);

    await user.type(screen.getByRole("searchbox"), "r");
    await user.selectOptions(screen.getByLabelText("Estado"), "INACTIVE");
    await user.selectOptions(screen.getByLabelText("Estado"), "Todos");

    expect(onSearch).toHaveBeenCalledWith("r");
    expect(onStatus).toHaveBeenNthCalledWith(1, "INACTIVE");
    expect(onStatus).toHaveBeenNthCalledWith(2, "");
  });
});
