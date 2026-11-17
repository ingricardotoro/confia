import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ledgerAccount } from "../../../test/contract-responses.ts";
import { formView } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { initialAccountValues } from "../account-form.ts";
import { LedgerAccountForm, type LedgerAccountFormProps } from "./LedgerAccountForm.tsx";

// States. AW57, AW58: the form of an account rendered with props alone. Loading: `form.submitting`, drawn by the dialog.
// Error: `fieldErrors` under each field. Empty does not apply: a form lists no data. Success: a create form and an edit
// form, which differ in the code, the type and the student flag (fixed once created) and in the active checkbox.
function props(override: Partial<LedgerAccountFormProps> = {}): LedgerAccountFormProps {
  return {
    mode: "create",
    values: initialAccountValues(),
    fieldErrors: {},
    form: formView(),
    onChange: vi.fn(),
    onBlur: vi.fn(),
    onClose: vi.fn(),
    ...override,
  };
}

describe("LedgerAccountForm", () => {
  it("a new account has a code, a name and a type, all required, and the student flag, and no active checkbox", () => {
    renderWithAdminI18n(<LedgerAccountForm {...props()} />);

    const dialog = screen.getByRole("dialog", { name: "Nueva cuenta contable" });
    expect(within(dialog).getByLabelText(/Código/)).toBeRequired();
    expect(within(dialog).getByLabelText(/Nombre/)).toBeRequired();
    expect(within(dialog).getByLabelText(/Tipo de cuenta/)).toBeRequired();
    expect(within(dialog).getByRole("checkbox", { name: "Lleva los saldos de los estudiantes" })).not.toBeChecked();
    expect(within(dialog).queryByRole("checkbox", { name: "Cuenta activa" })).not.toBeInTheDocument();
  });

  it("offers the five types, and the student flag only to the types that may carry it", async () => {
    const user = userEvent.setup();
    const { rerender } = renderWithAdminI18n(<LedgerAccountForm {...props({ values: { ...initialAccountValues(), type: "ASSET" } })} />);
    const select = screen.getByLabelText(/Tipo de cuenta/);

    expect(within(select).getAllByRole("option").map((option) => option.textContent)).toEqual(["Elija una opción", "Activo", "Pasivo", "Patrimonio", "Ingreso", "Gasto"]);
    expect(screen.getByRole("checkbox", { name: "Lleva los saldos de los estudiantes" })).toBeEnabled();

    rerender(<LedgerAccountForm {...props({ values: { ...initialAccountValues(), type: "INCOME", holdsStudentBalances: "true" } })} />);
    const flag = screen.getByRole("checkbox", { name: "Lleva los saldos de los estudiantes" });
    expect(flag).toBeDisabled();
    expect(flag).not.toBeChecked();
    expect(flag).toHaveAccessibleDescription(/Solo una cuenta de tipo Activo o Pasivo/);

    const onChange = vi.fn();
    rerender(<LedgerAccountForm {...props({ onChange })} />);
    await user.selectOptions(screen.getByLabelText(/Tipo de cuenta/), "LIABILITY");
    expect(onChange).toHaveBeenCalledWith("type", "LIABILITY");
  });

  it("an existing account shows its code, type and flag as read only and has the active checkbox and the name to edit", () => {
    const row = ledgerAccount({ code: "ACT-01", type: "ASSET", holdsStudentBalances: true });
    renderWithAdminI18n(<LedgerAccountForm {...props({ mode: "edit", values: initialAccountValues(row) })} />);

    const dialog = screen.getByRole("dialog", { name: "Editar cuenta contable" });
    expect(within(dialog).getByLabelText(/Código/)).toHaveValue("ACT-01");
    expect(within(dialog).getByLabelText(/Código/)).toHaveAttribute("readonly");
    expect(within(dialog).getByLabelText(/Tipo de cuenta/)).toHaveValue("Activo");
    expect(within(dialog).getByLabelText(/Tipo de cuenta/)).toHaveAttribute("readonly");
    expect(within(dialog).getByLabelText(/Lleva los saldos/)).toHaveValue("Sí");
    expect(within(dialog).getByLabelText(/Nombre/)).not.toHaveAttribute("readonly");
    expect(within(dialog).getByRole("checkbox", { name: "Cuenta activa" })).toBeChecked();
  });

  it("reports each change with the name of the field and a text value", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithAdminI18n(<LedgerAccountForm {...props({ onChange, values: { ...initialAccountValues(), type: "ASSET" } })} />);

    await user.type(screen.getByLabelText(/Código/), "C");
    await user.click(screen.getByRole("checkbox", { name: "Lleva los saldos de los estudiantes" }));

    expect(onChange).toHaveBeenCalledWith("code", "C");
    expect(onChange).toHaveBeenCalledWith("holdsStudentBalances", "true");
  });

  it("the error state describes each field with its message, the repeated code included", () => {
    renderWithAdminI18n(<LedgerAccountForm {...props({ fieldErrors: { code: "Ya existe una cuenta con ese código.", type: "Este dato es obligatorio." } })} />);

    expect(screen.getByLabelText(/Código/)).toHaveAccessibleDescription(/Ya existe una cuenta con ese código\./);
    expect(screen.getByLabelText(/Código/)).toBeInvalid();
    expect(screen.getByLabelText(/Tipo de cuenta/)).toHaveAccessibleDescription(/Este dato es obligatorio\./);
  });
});
