import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { renderWithAdminI18n } from "../../../../test/render.tsx";
import { FirstCodeStep, type FirstCodeStepProps } from "./FirstCodeStep.tsx";

// admin-web-staff-sign-in, task 8.1 (E10; SS92, SS94, SS97). The first code step as a presentation.
// States. Success: the empty field, the field with a value, and sending. Error: a code that is not accepted (the alert) and a
// field error with its summary, both shown here from props. Loading: the "sending" state, there is no read. Empty: not
// applicable (AW58), the step always has a secret to show again.
const noop = () => undefined;
const SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";
const URI = `otpauth://totp/CONFIA:ana%40colegio.edu.hn?secret=${SECRET}&issuer=CONFIA`;
const GROUPS = "JBSW Y3DP EHPK 3PXP JBSW Y3DP EHPK 3PXP";
const base: FirstCodeStepProps = {
  uri: URI,
  secret: SECRET,
  values: { code: "" },
  fieldErrors: {},
  summary: [],
  liveMessage: "",
  submitting: false,
  summaryRef: noop,
  alertRef: noop,
  onChange: noop,
  onBlur: noop,
  onSubmit: noop,
  onReviewSecret: noop,
};

function show(props: Partial<FirstCodeStepProps> = {}) {
  return renderWithAdminI18n(<FirstCodeStep {...base} {...props} />);
}

const codeField = () => screen.getByRole("textbox", { name: /Código de verificación/ });

describe("FirstCodeStep", () => {
  it("AW57, SS92: has its heading, one field for a one time code, the QR code and the key, Activate and the way back", async () => {
    show();

    await waitFor(() => {
      expect(screen.getByRole("heading", { level: 2, name: "Escriba el primer código" })).toHaveFocus();
    });
    expect(screen.getAllByRole("textbox")).toHaveLength(1);
    expect(codeField()).toHaveAttribute("inputmode", "numeric");
    expect(codeField()).toHaveAttribute("autocomplete", "one-time-code");
    expect(codeField()).toHaveAccessibleDescription("Escriba los 6 dígitos que muestra su aplicación.");
    expect(screen.getByRole("img", { name: "Código QR para su aplicación de autenticación" })).toBeInTheDocument();
    expect(screen.getByText(GROUPS)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Activar" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Ver de nuevo el código QR" })).toBeInTheDocument();
  });

  it("has no maxlength and no pattern: a paste with spaces must arrive whole to be normalized", () => {
    show();

    expect(codeField()).not.toHaveAttribute("maxlength");
    expect(codeField()).not.toHaveAttribute("pattern");
  });

  it("reports what is typed and the departure from the field", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    const onBlur = vi.fn();
    show({ onChange, onBlur });

    await user.type(codeField(), "7");
    await user.tab();

    expect(onChange).toHaveBeenCalledWith("code", "7");
    expect(onBlur).toHaveBeenCalledWith("code");
  });

  it("SS94: submits through the form, and the way back is a button that does not submit", async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn((event: { preventDefault: () => void }) => {
      event.preventDefault();
    });
    const onReviewSecret = vi.fn();
    show({ onSubmit, onReviewSecret });

    await user.click(screen.getByRole("button", { name: "Ver de nuevo el código QR" }));
    expect(onReviewSecret).toHaveBeenCalledOnce();
    expect(onSubmit).not.toHaveBeenCalled();

    await user.click(screen.getByRole("button", { name: "Activar" }));
    expect(onSubmit).toHaveBeenCalledOnce();
  });

  it("SS97: shows the alert of a code that is not accepted and keeps the QR code and the key in view", () => {
    show({ alert: { message: "El código de verificación no es correcto o ya expiró. Genere uno nuevo en su aplicación." }, values: { code: "123456" } });

    expect(screen.getByRole("alert")).toHaveTextContent("El código de verificación no es correcto o ya expiró.");
    expect(codeField()).toHaveValue("123456");
    expect(screen.getByRole("img", { name: "Código QR para su aplicación de autenticación" })).toBeInTheDocument();
    expect(screen.getByText(GROUPS)).toBeInTheDocument();
  });

  it("shows the error of the field and the summary that links to it", async () => {
    const user = userEvent.setup();
    show({
      fieldErrors: { code: "Este dato es obligatorio." },
      summary: [{ controlId: "enrollment-first-code", message: "Este dato es obligatorio." }],
    });

    await user.click(screen.getByRole("link", { name: "Este dato es obligatorio." }));

    expect(codeField()).toHaveFocus();
    expect(codeField()).toHaveAttribute("aria-invalid", "true");
  });

  it("marks the button busy while sending, and says so in its polite region", () => {
    show({ submitting: true, liveMessage: "Verificando el código. Esto puede tardar hasta 30 segundos." });

    expect(screen.getByRole("button", { name: /Activar/ })).toHaveAttribute("aria-busy", "true");
    expect(screen.getByText("Verificando el código. Esto puede tardar hasta 30 segundos.")).toHaveAttribute("aria-live", "polite");
  });
});
