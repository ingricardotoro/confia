import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { EnrollmentFilters, type EnrollmentFiltersProps } from "./EnrollmentFilters.tsx";

// States. AW57, AW58: the filters rendered with props alone. They load nothing: the lists behind the selects come as options,
// and the failure to load them is the screen's warning. Success: the four selects with their values; empty: the "all" options.
function props(override: Partial<EnrollmentFiltersProps> = {}): EnrollmentFiltersProps {
  return {
    year: { value: "y1", withAll: false, options: [{ value: "y1", label: "Año 2026" }], onChange: vi.fn() },
    grade: { value: "", options: [{ value: "g1", label: "Primaria · Primero" }], onChange: vi.fn() },
    section: { value: "", options: [{ value: "s1", label: "A" }], onChange: vi.fn() },
    status: { value: "", onChange: vi.fn() },
    ...override,
  };
}

describe("EnrollmentFilters", () => {
  it("AW57: shows the year in progress as chosen with no 'all' option, and every other filter on all", () => {
    renderWithAdminI18n(<EnrollmentFilters {...props()} />);

    expect(screen.getByLabelText("Año lectivo")).toHaveValue("y1");
    expect(within(screen.getByLabelText("Año lectivo")).queryByRole("option", { name: "Todos los años" })).not.toBeInTheDocument();
    expect(screen.getByLabelText("Grado")).toHaveValue("");
    expect(screen.getByLabelText("Sección")).toHaveValue("");
    expect(screen.getByLabelText("Estado")).toHaveValue("");
    expect(within(screen.getByLabelText("Estado")).getAllByRole("option").map((option) => option.textContent)).toEqual([
      "Todos",
      "Activa",
      "Retirada",
      "Trasladada a otra institución",
      "Graduada",
    ]);
  });

  it("AW58: offers every year when there is no year in progress, and reports each change", async () => {
    const user = userEvent.setup();
    const onYear = vi.fn();
    const onStatus = vi.fn();
    renderWithAdminI18n(
      <EnrollmentFilters
        {...props({
          year: { value: "", withAll: true, options: [{ value: "y1", label: "Año 2026" }], onChange: onYear },
          status: { value: "", onChange: onStatus },
        })}
      />,
    );

    expect(within(screen.getByLabelText("Año lectivo")).getByRole("option", { name: "Todos los años" })).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText("Año lectivo"), "y1");
    await user.selectOptions(screen.getByLabelText("Estado"), "WITHDRAWN");

    expect(onYear).toHaveBeenCalledWith("y1");
    expect(onStatus).toHaveBeenCalledWith("WITHDRAWN");
  });
});
