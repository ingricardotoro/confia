import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createRef } from "react";
import { describe, expect, it, vi } from "vitest";
import { paymentBody } from "../../../test/contract-responses.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import type { AccessFormView } from "../../../shared/forms/use-access-form.ts";
import type { RegisterField } from "../payments-model.ts";
import { RegisterPaymentView, type RegisterPaymentViewProps } from "./RegisterPaymentView.tsx";

// States. AW57, AW58: the register form rendered with props alone. Loading: the button waits while the request is in flight (and the charges show their own
// skeleton). Error: the alert and the summary of the form, and the field errors under their fields. Empty does not apply to the form (the charges panel has
// its own). Success: the result of the server replaces the form. Cash with no open cashbox says so and offers the way to it, and a transfer asks for its
// reference and bank.
function formOf(values: Partial<Record<RegisterField, string>> = {}, override: Partial<AccessFormView<RegisterField>> = {}): AccessFormView<RegisterField> {
  return {
    values: { studentId: "", method: "CASH", amount: "", receivedOn: "2026-11-20", reference: "", bankName: "", notes: "", ...values },
    fieldErrors: {},
    summary: [],
    liveMessage: "",
    submitting: false,
    summaryRef: createRef<HTMLDivElement>(),
    alertRef: createRef<HTMLDivElement>(),
    onChange: vi.fn(),
    onBlur: vi.fn(),
    onSubmit: vi.fn((event: { preventDefault: () => void }) => {
      event.preventDefault();
    }),
    ...override,
  };
}

/** What the container hands the view from a form: its state, its values and errors, and its handlers. */
function partsOf(form: AccessFormView<RegisterField>): Pick<RegisterPaymentViewProps, "form" | "values" | "fieldErrors" | "onChange" | "onBlur"> {
  return { form, values: form.values, fieldErrors: form.fieldErrors, onChange: form.onChange, onBlur: form.onBlur };
}

function props(override: Partial<RegisterPaymentViewProps> = {}): RegisterPaymentViewProps {
  return {
    ...partsOf(formOf()),
    picker: {
      controlId: "payments-register-student",
      draft: "",
      onDraftChange: vi.fn(),
      tooShort: false,
      search: "idle",
      results: [],
      onRetry: vi.fn(),
      chosenPending: false,
      onChoose: vi.fn(),
      onClear: vi.fn(),
    },
    charges: undefined,
    balance: { kind: "loading" },
    cashbox: "open",
    onGoToCashbox: vi.fn(),
    onViewCharges: vi.fn(),
    onNew: vi.fn(),
    onViewPayment: vi.fn(),
    onViewStatement: vi.fn(),
    ...override,
  };
}

describe("RegisterPaymentView", () => {
  it("AW57: asks for the method, the amount and the day, with no reference for cash, and reports what is typed", async () => {
    const user = userEvent.setup();
    const form = formOf();
    renderWithAdminI18n(<RegisterPaymentView {...props(partsOf(form))} />);

    expect(screen.queryByRole("textbox", { name: /Referencia bancaria/ })).not.toBeInTheDocument();
    await user.type(screen.getByRole("textbox", { name: /Importe recibido/ }), "5");
    expect(form.onChange).toHaveBeenCalledWith("amount", "5");
    await user.click(screen.getByRole("button", { name: "Registrar pago" }));
    expect(form.onSubmit).toHaveBeenCalled();
  });

  it("AW57: asks a transfer for its reference and bank and says it does not change the balance until confirmed", () => {
    renderWithAdminI18n(<RegisterPaymentView {...props({ ...partsOf(formOf({ method: "BANK_TRANSFER" })) })} />);

    expect(screen.getByRole("textbox", { name: /Referencia bancaria/ })).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: /Banco/ })).toBeInTheDocument();
    expect(screen.getByText(/no cambia el saldo hasta que una persona autorizada la confirme/)).toBeInTheDocument();
  });

  it("AW58: says cash needs an open cashbox, offers the way to it and keeps the button from sending, but not for a transfer", async () => {
    const user = userEvent.setup();
    const onGoToCashbox = vi.fn();
    const { rerender } = renderWithAdminI18n(<RegisterPaymentView {...props({ cashbox: "none", onGoToCashbox })} />);

    expect(screen.getByText("No tiene una caja abierta")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Registrar pago" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Ir a Mi caja" }));
    expect(onGoToCashbox).toHaveBeenCalled();

    rerender(<RegisterPaymentView {...props({ cashbox: "none", ...partsOf(formOf({ method: "BANK_TRANSFER" })) })} />);
    expect(screen.queryByText("No tiene una caja abierta")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Registrar pago" })).toBeEnabled();
  });

  it("AW58: waits while it sends, and shows the alert, the summary and the field errors the form holds", () => {
    renderWithAdminI18n(
      <RegisterPaymentView
        {...props({
          ...partsOf(formOf({}, { submitting: true, liveMessage: "Registrando el pago.", alert: { message: "Faltan las cuentas de caja o de banco.", traceId: "01J8" }, summary: [{ controlId: "payments-register-amount", message: "El importe debe ser mayor que cero." }], fieldErrors: { amount: "El importe debe ser mayor que cero.", studentId: "Este dato es obligatorio." } })),
        })}
      />,
    );

    expect(screen.getByRole("button", { name: /Registrar pago/ })).toHaveAttribute("aria-busy", "true");
    expect(screen.getByText("Faltan las cuentas de caja o de banco.")).toBeInTheDocument();
    expect(screen.getAllByText("El importe debe ser mayor que cero.").length).toBeGreaterThan(0);
    expect(screen.getByText("Este dato es obligatorio.")).toBeInTheDocument();
    expect(screen.getByText("Registrando el pago.")).toBeInTheDocument();
  });

  it("AW57: shows the chosen student with the way to change them, and their charges", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithAdminI18n(
      <RegisterPaymentView
        {...props({
          ...partsOf(formOf({ studentId: "00000000-0000-4000-8000-000000005001" })),
          chosen: { label: "Aguilar, Ana (E-000001)", onChange },
          charges: { kind: "ready", rows: [], pagination: { page: 0, size: 10, total: 0, onPageChange: () => undefined, busy: false } },
        })}
      />,
    );

    expect(screen.getByText("Aguilar, Ana (E-000001)")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Cambiar estudiante" }));
    expect(onChange).toHaveBeenCalled();
    expect(screen.getByText("El estudiante no tiene cargos pendientes")).toBeInTheDocument();
  });

  it("AW57: replaces the form with the result the server computed", () => {
    renderWithAdminI18n(<RegisterPaymentView {...props({ result: paymentBody() })} />);

    expect(screen.getByRole("heading", { name: "Pago registrado" })).toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: /Importe recibido/ })).not.toBeInTheDocument();
  });
});
