import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { formView } from "../../../test/form-view.ts";
import { renderWithAdminI18n } from "../../../test/render.tsx";
import { OpenSessionForm } from "./OpenSessionForm.tsx";

// States. AW57, AW58: the form that opens a session rendered with props alone. Loading: while the request is in flight the button waits and the form says so
// in its polite region. Empty: nothing typed. Error: the summary and the field error of a float that does not hold, and the alert of a refusal of the server.
// Success: a float typed.
const props = { openingFloat: "", onChange: vi.fn(), onBlur: vi.fn() };

describe("OpenSessionForm", () => {
  it("AW57: names the field, explains the amount and reports what is typed", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithAdminI18n(<OpenSessionForm {...props} onChange={onChange} form={formView()} />);

    const field = screen.getByRole("textbox", { name: /Fondo inicial/ });
    expect(field).toBeRequired();
    expect(field).toHaveAccessibleDescription(/hasta dos decimales/);
    await user.type(field, "5");

    expect(onChange).toHaveBeenCalledWith("5");
  });

  it("describes the error under the field and lists it in the summary", () => {
    renderWithAdminI18n(
      <OpenSessionForm
        {...props}
        error="Escriba el importe con punto decimal."
        form={formView({ summary: [{ controlId: "cashbox-opening-float", message: "Escriba el importe con punto decimal." }] })}
      />,
    );

    expect(screen.getByRole("textbox", { name: /Fondo inicial/ })).toBeInvalid();
    expect(screen.getAllByText("Escriba el importe con punto decimal.").length).toBeGreaterThan(1);
  });

  it("tells a refusal of the server in an alert with the trace id", () => {
    renderWithAdminI18n(<OpenSessionForm {...props} form={formView({ alert: { message: "Ya tiene una caja abierta.", traceId: "01J8Z4K2QF9P0X" } })} />);

    expect(screen.getByText("Ya tiene una caja abierta.")).toBeInTheDocument();
    expect(screen.getByText(/01J8Z4K2QF9P0X/)).toBeInTheDocument();
  });

  it("marks the button busy and says so while the request is in flight", () => {
    renderWithAdminI18n(<OpenSessionForm {...props} form={formView({ submitting: true, liveMessage: "Abriendo la caja." })} />);

    expect(screen.getByRole("button", { name: /Abrir caja/ })).toHaveAttribute("aria-busy", "true");
    expect(screen.getByText("Abriendo la caja.")).toBeInTheDocument();
  });
});
