import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { renderWithI18n } from "../../test/render";
import { Select } from "./Select";

describe("Select", () => {
  it("is a combobox named by its label", () => {
    renderWithI18n(
      <>
        <label htmlFor="modality">Modalidad</label>
        <Select id="modality">
          <option value="a">Regular</option>
        </Select>
      </>,
    );

    expect(screen.getByRole("combobox", { name: "Modalidad" })).toBeVisible();
  });

  it("takes the focus from the keyboard and reports the chosen option", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithI18n(
      <Select aria-label="Modalidad" onChange={onChange}>
        <option value="a">Regular</option>
        <option value="b">Nocturna</option>
      </Select>,
    );

    await user.tab();
    expect(screen.getByRole("combobox", { name: "Modalidad" })).toHaveFocus();
    await user.selectOptions(screen.getByRole("combobox", { name: "Modalidad" }), "Nocturna");

    expect(onChange).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("combobox", { name: "Modalidad" })).toHaveValue("b");
  });

  it("announces the invalid state and honors disabled", () => {
    renderWithI18n(
      <Select aria-label="Modalidad" aria-invalid disabled>
        <option value="a">Regular</option>
      </Select>,
    );

    const select = screen.getByRole("combobox", { name: "Modalidad" });
    expect(select).toBeInvalid();
    expect(select).toBeDisabled();
  });

  it("merges the class of the caller onto the select", () => {
    renderWithI18n(
      <Select aria-label="Modalidad" className="w-40">
        <option value="a">Regular</option>
      </Select>,
    );

    expect(screen.getByRole("combobox", { name: "Modalidad" })).toHaveClass("w-40");
  });
});
