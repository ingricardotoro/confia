import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { discount } from "../../../test/contract-responses.ts";
import { formView } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { initialDiscountValues } from "../discount-form.ts";
import { DiscountForm, type DiscountFormProps } from "./DiscountForm.tsx";

// States. AW57, AW58: The form of a discount rendered with props alone. Loading: `form.submitting`, drawn by the dialog. Error: `fieldErrors` under each field and the
// group of concepts, and the failure of the lookup of concepts with its retry. Empty: no active concept to choose. Success: a create form and an edit
// form, which differ in the code and the class (fixed once created) and in the benefit (read only while the discount is in use).
const OPTIONS = [
  { value: "00000000-0000-4000-8000-000000007001", label: "MENS · Mensualidad" },
  { value: "00000000-0000-4000-8000-000000007002", label: "MATR · Matrícula" },
];

function props(override: Partial<DiscountFormProps> = {}): DiscountFormProps {
  return {
    mode: "create",
    values: initialDiscountValues(),
    fieldErrors: {},
    form: formView(),
    conceptOptions: OPTIONS,
    noConcepts: false,
    inUse: false,
    onChange: vi.fn(),
    onBlur: vi.fn(),
    onClose: vi.fn(),
    ...override,
  };
}

describe("DiscountForm", () => {
  it("asks a new discount for a code, a class, a name, a type and the validity, all required, and the value only once the type is chosen", () => {
    const { rerender } = renderWithAdminI18n(<DiscountForm {...props()} />);

    const dialog = screen.getByRole("dialog", { name: "Crear un descuento" });
    for (const label of [/^Código/, /^Clase/, /^Nombre/, /^Tipo de beneficio/, /^Vigente desde/, /^Vigente hasta/]) {
      expect(within(dialog).getByLabelText(label)).toBeRequired();
    }
    expect(within(dialog).queryByLabelText(/^Porcentaje/)).not.toBeInTheDocument();
    expect(within(dialog).queryByLabelText(/^Monto fijo/)).not.toBeInTheDocument();

    rerender(<DiscountForm {...props({ values: { ...initialDiscountValues(), benefitType: "FIXED_AMOUNT" } })} />);
    expect(within(screen.getByRole("dialog")).getByLabelText(/^Monto fijo por cargo/)).toBeRequired();
  });

  it("offers the two classes and a stackable mark that starts unchecked", () => {
    renderWithAdminI18n(<DiscountForm {...props()} />);

    const dialog = within(screen.getByRole("dialog"));
    expect(dialog.getByRole("option", { name: "Campaña" })).toBeInTheDocument();
    expect(dialog.getByRole("option", { name: "Convenio" })).toBeInTheDocument();
    expect(dialog.getByRole("checkbox", { name: "Acumulable con los demás beneficios" })).not.toBeChecked();
  });

  it("shows an existing discount with its code and its class as read only and its concepts chosen", () => {
    const row = discount({ kind: "agreement", stackable: true });
    renderWithAdminI18n(<DiscountForm {...props({ mode: "edit", values: initialDiscountValues(row) })} />);

    const dialog = within(screen.getByRole("dialog", { name: "Editar el descuento" }));
    expect(dialog.getByLabelText(/^Código/)).toHaveAttribute("readonly");
    expect(dialog.getByLabelText(/^Clase/)).toHaveAttribute("readonly");
    expect(dialog.getByLabelText(/^Clase/)).toHaveValue("Convenio");
    expect(dialog.getByLabelText(/^Porcentaje/)).toHaveValue("10.00");
    expect(dialog.getByRole("checkbox", { name: "Acumulable con los demás beneficios" })).toBeChecked();
    expect(dialog.getByRole("checkbox", { name: "MENS · Mensualidad" })).toBeChecked();
    expect(dialog.getByRole("checkbox", { name: "MATR · Matrícula" })).not.toBeChecked();
  });

  it("locks the type, the value and the stackable mark of a discount in use, shows them as read only and says why", () => {
    const row = discount({ inUse: true });
    renderWithAdminI18n(<DiscountForm {...props({ mode: "edit", inUse: true, values: initialDiscountValues(row) })} />);

    const dialog = within(screen.getByRole("dialog", { name: "Editar el descuento" }));
    expect(dialog.getByText(/ya está en uso .*su tipo, su valor y su marca de acumulable no se pueden cambiar, la vigencia solo puede acortarse y los conceptos solo pueden quitarse/)).toBeInTheDocument();
    expect(dialog.getByLabelText(/^Porcentaje/)).toHaveAttribute("readonly");
    expect(dialog.getByLabelText(/^Tipo de beneficio/)).toHaveValue("Porcentaje");
    expect(dialog.queryByRole("combobox", { name: /Tipo de beneficio/ })).not.toBeInTheDocument();
    // S8: the stackable mark is shown as text, read only, and cannot be toggled.
    expect(dialog.queryByRole("checkbox", { name: "Acumulable con los demás beneficios" })).not.toBeInTheDocument();
    expect(dialog.getByLabelText("Acumulable con los demás beneficios")).toHaveAttribute("readonly");
    expect(dialog.getByLabelText("Acumulable con los demás beneficios")).toHaveValue("Acumulable");
    // What can still change is still editable: the name and the validity.
    expect(dialog.getByLabelText(/^Nombre/)).not.toHaveAttribute("readonly");
    expect(dialog.getByLabelText(/^Vigente hasta/)).not.toHaveAttribute("readonly");
  });

  it("shows a discount in use that does not stack as not stackable, still read only", () => {
    const row = discount({ inUse: true, stackable: false });
    renderWithAdminI18n(<DiscountForm {...props({ mode: "edit", inUse: true, values: initialDiscountValues(row) })} />);

    expect(screen.getByLabelText("Acumulable con los demás beneficios")).toHaveValue("No acumulable");
  });

  it("keeps the stackable mark editable while the discount is not in use", () => {
    renderWithAdminI18n(<DiscountForm {...props({ mode: "edit", values: initialDiscountValues(discount({ stackable: true })) })} />);

    expect(within(screen.getByRole("dialog")).getByRole("checkbox", { name: "Acumulable con los demás beneficios" })).toBeEnabled();
  });

  it("tells a campaign discount that it needs another person to approve it, and an agreement discount nothing of the kind", () => {
    const { rerender } = renderWithAdminI18n(<DiscountForm {...props({ values: { ...initialDiscountValues(), kind: "campaign" } })} />);
    expect(screen.getByText(/nace pendiente y no se aplica a ningún cargo hasta que otra persona con permiso de aprobación lo apruebe/)).toBeInTheDocument();

    rerender(<DiscountForm {...props({ values: { ...initialDiscountValues(), kind: "agreement" } })} />);
    expect(screen.queryByText(/nace pendiente/)).not.toBeInTheDocument();
  });

  it("leaves the type and the value editable while the discount is not in use", () => {
    renderWithAdminI18n(<DiscountForm {...props({ mode: "edit", values: initialDiscountValues(discount()) })} />);

    const dialog = within(screen.getByRole("dialog"));
    expect(dialog.queryByText(/ya está en uso/)).not.toBeInTheDocument();
    expect(dialog.getByLabelText(/^Porcentaje/)).not.toHaveAttribute("readonly");
  });

  it("reports each change with the name of the field, the stackable mark as text, and the concepts as one text of ids in the order of the options", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithAdminI18n(<DiscountForm {...props({ onChange, values: { ...initialDiscountValues(), conceptIds: OPTIONS[1]?.value ?? "" } })} />);

    await user.type(screen.getByLabelText(/^Código/), "C");
    await user.selectOptions(screen.getByLabelText(/^Clase/), "agreement");
    await user.click(screen.getByRole("checkbox", { name: "Acumulable con los demás beneficios" }));
    await user.click(screen.getByRole("checkbox", { name: "MENS · Mensualidad" }));

    expect(onChange).toHaveBeenCalledWith("code", "C");
    expect(onChange).toHaveBeenCalledWith("kind", "agreement");
    expect(onChange).toHaveBeenCalledWith("stackable", "true");
    expect(onChange).toHaveBeenCalledWith("conceptIds", `${OPTIONS[0]?.value ?? ""},${OPTIONS[1]?.value ?? ""}`);
  });

  it("shows the error of each field in words, and the one of the concepts under their group", () => {
    renderWithAdminI18n(
      <DiscountForm
        {...props({
          values: { ...initialDiscountValues(), benefitType: "PERCENTAGE" },
          fieldErrors: { name: "Este dato es obligatorio.", validTo: "Hay asignaciones pendientes o aprobadas que quedarían fuera de esta vigencia.", conceptIds: "Elija al menos un concepto." },
        })}
      />,
    );

    const dialog = within(screen.getByRole("dialog"));
    expect(dialog.getByLabelText(/^Nombre/)).toBeInvalid();
    expect(dialog.getByLabelText(/^Vigente hasta/)).toBeInvalid();
    expect(dialog.getByText(/quedarían fuera de esta vigencia/)).toBeInTheDocument();
    expect(within(dialog.getByRole("group", { name: "Conceptos que cubre" })).getByText("Elija al menos un concepto.")).toBeInTheDocument();
  });

  it("says there is no active concept to choose, and offers to retry a failed lookup of concepts", async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    renderWithAdminI18n(<DiscountForm {...props({ conceptOptions: [], noConcepts: true, conceptLookupFailure: { onRetry } })} />);

    expect(screen.getByText(/No hay conceptos de pago activos/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Reintentar" }));
    expect(onRetry).toHaveBeenCalled();
  });
});
