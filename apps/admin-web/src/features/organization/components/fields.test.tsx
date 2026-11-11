import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { ActiveBadge, OptionField, ReadOnlyField, RowActions, TextField } from "./fields.tsx";

// States. AW57, AW58: the pieces of the forms and of the rows, rendered with props alone. Loading does not apply:
// they hold no data of their own. Error: a field with `error` is invalid and described by its message. Empty:
// a select with no options still offers its placeholder. Success: the field with a value; a row action that is
// `busy`.
describe("TextField", () => {
  it("AW57: names the control with its label, marks it required and writes what is typed", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithAdminI18n(<TextField label="Nombre" controlId="name" value="" required onChange={onChange} onBlur={vi.fn()} />);

    const input = screen.getByLabelText(/Nombre/);
    expect(input).toHaveAttribute("id", "name");
    expect(input).toBeRequired();
    await user.type(input, "A");

    expect(onChange).toHaveBeenCalledWith("A");
  });

  it("describes an error and marks the control invalid, in text and not only in color", () => {
    renderWithAdminI18n(<TextField label="Nombre" controlId="name" value="" error="Este dato es obligatorio." onChange={vi.fn()} onBlur={vi.fn()} />);

    expect(screen.getByLabelText("Nombre")).toBeInvalid();
    expect(screen.getByLabelText("Nombre")).toHaveAccessibleDescription("Este dato es obligatorio.");
  });

  it("is the native date input for a date, and text with the numeric keyboard for a number", () => {
    renderWithAdminI18n(
      <>
        <TextField label="Fecha" controlId="date" value="" kind="date" onChange={vi.fn()} onBlur={vi.fn()} />
        <TextField label="Orden" controlId="ordinal" value="" kind="numeric" onChange={vi.fn()} onBlur={vi.fn()} />
      </>,
    );

    expect(screen.getByLabelText("Fecha")).toHaveAttribute("type", "date");
    expect(screen.getByLabelText("Orden")).toHaveAttribute("type", "text");
    expect(screen.getByLabelText("Orden")).toHaveAttribute("inputmode", "numeric");
  });
});

describe("OptionField", () => {
  it("offers the placeholder first and then each option, and reports the value chosen", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithAdminI18n(
      <OptionField
        label="Modalidad"
        controlId="modality"
        value=""
        options={[{ value: "1", label: "Primaria" }]}
        placeholder="Todos"
        onChange={onChange}
      />,
    );

    expect(screen.getAllByRole("option").map((option) => option.textContent)).toEqual(["Todos", "Primaria"]);
    await user.selectOptions(screen.getByLabelText("Modalidad"), "1");

    expect(onChange).toHaveBeenCalledWith("1");
  });

  it("with no options and no placeholder it has nothing to choose, and with only a placeholder it offers just that", () => {
    const { rerender } = renderWithAdminI18n(<OptionField label="Modalidad" controlId="modality" value="" options={[]} onChange={vi.fn()} />);
    expect(screen.queryAllByRole("option")).toHaveLength(0);

    rerender(<OptionField label="Modalidad" controlId="modality" value="" options={[]} placeholder="Elija una opción" onChange={vi.fn()} />);
    expect(screen.getAllByRole("option").map((option) => option.textContent)).toEqual(["Elija una opción"]);
  });

  it("describes an error and marks the select invalid", () => {
    renderWithAdminI18n(
      <OptionField label="Modalidad" controlId="modality" value="" options={[]} placeholder="Elija" error="Este dato es obligatorio." onChange={vi.fn()} />,
    );

    expect(screen.getByLabelText("Modalidad")).toBeInvalid();
    expect(screen.getByLabelText("Modalidad")).toHaveAccessibleDescription("Este dato es obligatorio.");
  });
});

describe("ReadOnlyField", () => {
  it("shows a value that cannot be changed, and says so in its description", () => {
    renderWithAdminI18n(<ReadOnlyField label="Modalidad" controlId="modality" value="Primaria" />);

    const input = screen.getByLabelText("Modalidad");
    expect(input).toHaveValue("Primaria");
    expect(input).toHaveAttribute("readonly");
    expect(input).toHaveAccessibleDescription("Este dato no se puede cambiar después de crear el registro.");
  });
});

describe("ActiveBadge", () => {
  it("says the state in text", () => {
    const { rerender } = renderWithAdminI18n(<ActiveBadge active />);
    expect(screen.getByText("Activo")).toBeInTheDocument();

    rerender(<ActiveBadge active={false} />);
    expect(screen.getByText("Inactivo")).toBeInTheDocument();
  });
});

describe("RowActions", () => {
  it("names each button with the record it acts on, and reports which was pressed", async () => {
    const user = userEvent.setup();
    const onEdit = vi.fn();
    const onToggle = vi.fn();
    renderWithAdminI18n(<RowActions name="Año 2026" active busy={false} onEdit={onEdit} onToggle={onToggle} />);

    await user.click(screen.getByRole("button", { name: "Editar Año 2026" }));
    await user.click(screen.getByRole("button", { name: "Desactivar Año 2026" }));

    expect(onEdit).toHaveBeenCalledTimes(1);
    expect(onToggle).toHaveBeenCalledTimes(1);
  });

  it("offers to activate an inactive record", () => {
    renderWithAdminI18n(<RowActions name="Año 2025" active={false} busy={false} onEdit={vi.fn()} onToggle={vi.fn()} />);

    expect(screen.getByRole("button", { name: "Activar Año 2025" })).toHaveTextContent("Activar");
  });

  it("marks the toggle busy while it is saving, and does not send it twice", async () => {
    const user = userEvent.setup();
    const onToggle = vi.fn();
    renderWithAdminI18n(<RowActions name="Año 2026" active busy onEdit={vi.fn()} onToggle={onToggle} />);

    const toggle = screen.getByRole("button", { name: "Desactivar Año 2026" });
    expect(toggle).toHaveAttribute("aria-busy", "true");
    await user.click(toggle);

    expect(onToggle).not.toHaveBeenCalled();
  });
});
