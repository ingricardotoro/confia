import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { formView } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { initialPriceListValues } from "../price-list-form.ts";
import { PriceListForm, type PriceListFormProps } from "./PriceListForm.tsx";

// States. AW57, AW58: the form of a new price list rendered with props alone. Loading: `form.submitting`, drawn by the
// dialog. Error: `fieldErrors` under each field and `lookupFailure` for the options. Empty does not apply: a form lists
// no data. Success: the form for the institution, and the form for a modality and for a grade, which ask for a reference.
const YEAR = "00000000-0000-4000-8000-000000000a01";

function props(override: Partial<PriceListFormProps> = {}): PriceListFormProps {
  return {
    values: initialPriceListValues(),
    fieldErrors: {},
    form: formView(),
    yearOptions: [{ value: YEAR, label: "Año 2027" }],
    modalityOptions: [{ value: "m-1", label: "Diurna" }],
    gradeOptions: [{ value: "g-1", label: "Primer grado" }],
    onChange: vi.fn(),
    onBlur: vi.fn(),
    onClose: vi.fn(),
    ...override,
  };
}

describe("PriceListForm", () => {
  it("asks for the year, the scope and the name, and for no reference while the scope is the whole institution", () => {
    renderWithAdminI18n(<PriceListForm {...props()} />);

    const dialog = screen.getByRole("dialog", { name: "Nueva lista de precios" });
    expect(within(dialog).getByLabelText(/Año lectivo/)).toBeRequired();
    expect(within(dialog).getByLabelText(/Ámbito/)).toHaveValue("INSTITUTION");
    expect(within(dialog).getByLabelText(/Nombre/)).toBeRequired();
    expect(within(dialog).queryByLabelText(/Modalidad/)).not.toBeInTheDocument();
    expect(within(dialog).queryByLabelText(/Grado/)).not.toBeInTheDocument();
  });

  it("asks for a modality when the scope is a modality, with its choices", () => {
    renderWithAdminI18n(<PriceListForm {...props({ values: { ...initialPriceListValues(), scope: "MODALITY" } })} />);

    const field = screen.getByLabelText(/Modalidad/);
    expect(field).toBeRequired();
    expect(within(field).getAllByRole("option").map((option) => option.textContent)).toEqual(["Elija una opción", "Diurna"]);
  });

  it("asks for a grade when the scope is a grade, with its choices", () => {
    renderWithAdminI18n(<PriceListForm {...props({ values: { ...initialPriceListValues(), scope: "GRADE" } })} />);

    expect(within(screen.getByLabelText(/Grado/)).getAllByRole("option").map((option) => option.textContent)).toEqual(["Elija una opción", "Primer grado"]);
  });

  it("reports each change with the name of the field", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithAdminI18n(<PriceListForm {...props({ onChange })} />);

    await user.selectOptions(screen.getByLabelText(/Año lectivo/), YEAR);
    await user.selectOptions(screen.getByLabelText(/Ámbito/), "GRADE");
    await user.type(screen.getByLabelText(/Nombre/), "2");

    expect(onChange).toHaveBeenCalledWith("academicYearId", YEAR);
    expect(onChange).toHaveBeenCalledWith("scope", "GRADE");
    expect(onChange).toHaveBeenCalledWith("name", "2");
  });

  it("the error state describes each field with its message", () => {
    renderWithAdminI18n(<PriceListForm {...props({ fieldErrors: { academicYearId: "Este dato es obligatorio.", name: "Este dato es demasiado largo." } })} />);

    expect(screen.getByLabelText(/Año lectivo/)).toHaveAccessibleDescription(/Este dato es obligatorio\./);
    expect(screen.getByLabelText(/Nombre/)).toHaveAccessibleDescription(/Este dato es demasiado largo\./);
  });

  it("warns, with a retry, when the options failed to load", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderWithAdminI18n(<PriceListForm {...props({ lookupFailure: { onRetry } })} />);

    await user.click(screen.getByRole("button", { name: "Reintentar" }));

    expect(onRetry).toHaveBeenCalledTimes(1);
  });
});
