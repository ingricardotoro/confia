import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { StudentFilters, type StudentFiltersProps } from "./StudentFilters.tsx";

// States. AW57, AW58: the filters of the students list rendered with props alone. Loading, empty and success do not
// apply: they list no data. Error: the hint under the search turns into the warning that two characters are needed when
// `search.tooShort` is set.
function props(override: Partial<StudentFiltersProps> = {}): StudentFiltersProps {
  return { search: { value: "", onChange: vi.fn(), tooShort: false }, status: { value: "", onChange: vi.fn() }, ...override };
}

describe("StudentFilters", () => {
  it("AW57: has a search box with what it looks in, and a status select that starts at All", () => {
    renderWithAdminI18n(<StudentFilters {...props()} />);

    const search = screen.getByRole("searchbox", { name: "Buscar" });
    expect(search).toHaveAccessibleDescription("Por nombre, apellido o código.");
    expect(search).toHaveAttribute("autocomplete", "off");
    const status = screen.getByLabelText("Estado");
    expect(within(status).getAllByRole("option").map((option) => option.textContent)).toEqual(["Todos", "Activo", "Inactivo"]);
    expect(status).toHaveValue("");
  });

  it("says that two characters are needed when one is typed", () => {
    renderWithAdminI18n(<StudentFilters {...props({ search: { value: "a", onChange: vi.fn(), tooShort: true } })} />);

    expect(screen.getByRole("searchbox", { name: "Buscar" })).toHaveAccessibleDescription("Escriba al menos 2 caracteres para buscar.");
  });

  it("shows what is typed and reports every change", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithAdminI18n(<StudentFilters {...props({ search: { value: "", onChange, tooShort: false } })} />);

    await user.type(screen.getByRole("searchbox", { name: "Buscar" }), "pe");

    expect(onChange).toHaveBeenNthCalledWith(1, "p");
    expect(onChange).toHaveBeenNthCalledWith(2, "e");
  });

  it("reports the status chosen, and empty when All is chosen again", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithAdminI18n(<StudentFilters {...props({ status: { value: "ACTIVE", onChange } })} />);
    expect(screen.getByLabelText("Estado")).toHaveValue("ACTIVE");

    await user.selectOptions(screen.getByLabelText("Estado"), "INACTIVE");
    await user.selectOptions(screen.getByLabelText("Estado"), "");

    expect(onChange).toHaveBeenNthCalledWith(1, "INACTIVE");
    expect(onChange).toHaveBeenNthCalledWith(2, "");
  });
});
