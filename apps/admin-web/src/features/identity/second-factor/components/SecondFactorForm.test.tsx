import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { renderWithAdminI18n } from "../../../../test/render.tsx";
import { SecondFactorForm, type SecondFactorFormProps } from "./SecondFactorForm.tsx";

// admin-web-staff-sign-in, task 5.1 (E9; SS61, SS64, SS65, SS73). The verification screen as a presentation.
// States. Success: the TOTP mode, the recovery code mode, one with a general error, one with a trace id, one with field errors and its summary,
// one waiting (a count after a 429) and one sending. Loading and empty: not applicable, it reads nothing; the request of the
// form is the "sending" state. The error of a request is the alert and the field errors, shown here from props.
const noop = () => undefined;
const base: SecondFactorFormProps = {
  mode: "code",
  values: { code: "", recoveryCode: "" },
  fieldErrors: {},
  summary: [],
  liveMessage: "",
  submitting: false,
  focusField: false,
  summaryRef: noop,
  alertRef: noop,
  onChange: noop,
  onBlur: noop,
  onSubmit: noop,
  onToggleMode: noop,
  onBack: noop,
};

function show(props: Partial<SecondFactorFormProps> = {}) {
  return renderWithAdminI18n(<SecondFactorForm {...base} {...props} />);
}

const codeField = () => screen.getByRole("textbox", { name: /Código de verificación/ });
const recoveryField = () => screen.getByRole("textbox", { name: /Código de recuperación/ });

describe("SecondFactorForm: the TOTP mode", () => {
  it("AW57, SS61: has one level 1 heading and one field with a visible label, a hint and the attributes of a one time code", () => {
    show();

    expect(screen.getByRole("heading", { level: 1, name: "Verificación en dos pasos" })).toBeInTheDocument();
    expect(screen.getAllByRole("textbox")).toHaveLength(1);
    expect(codeField()).toHaveAttribute("inputmode", "numeric");
    expect(codeField()).toHaveAttribute("autocomplete", "one-time-code");
    expect(codeField()).toHaveAccessibleDescription("Escriba los 6 dígitos que muestra su aplicación.");
    expect(screen.getByText("Obligatorio")).toBeInTheDocument();
  });

  it("SS63: has no maxlength and no pattern, and the form does not validate by itself", () => {
    show();

    expect(codeField()).not.toHaveAttribute("maxlength");
    expect(codeField()).not.toHaveAttribute("pattern");
    expect(screen.getByRole("button", { name: "Verificar" }).closest("form")).toHaveAttribute("novalidate");
  });

  it("the texts of the buttons are the approved ones of design section 8", () => {
    show();

    expect(screen.getByRole("button", { name: "Verificar" })).toHaveAttribute("type", "submit");
    expect(screen.getByRole("button", { name: "Usar un código de recuperación" })).toHaveAttribute("type", "button");
    expect(screen.getByRole("button", { name: "Volver al inicio de sesión" })).toHaveAttribute("type", "button");
  });

  it("shows the value it is given and reports what is typed and when the field is left", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    const onBlur = vi.fn();
    show({ values: { code: "123", recoveryCode: "" }, onChange, onBlur });
    expect(codeField()).toHaveValue("123");

    await user.type(codeField(), "4");
    await user.tab();

    expect(onChange).toHaveBeenCalledWith("code", "1234");
    expect(onBlur).toHaveBeenCalledWith("code");
  });
});

describe("SecondFactorForm: the recovery code mode", () => {
  it("SS64, SS65: only the recovery field is mounted, with a visible label, no autofill and capitals for the keyboard", () => {
    show({ mode: "recovery-code" });

    expect(screen.getAllByRole("textbox")).toHaveLength(1);
    expect(recoveryField()).toHaveAttribute("autocomplete", "off");
    expect(recoveryField()).toHaveAttribute("autocapitalize", "characters");
    expect(recoveryField()).toHaveAttribute("spellcheck", "false");
    expect(screen.queryByRole("textbox", { name: /Código de verificación/ })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Usar el código de la aplicación" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Usar un código de recuperación" })).not.toBeInTheDocument();
  });

  it("SS63: the recovery field has no maxlength either", () => {
    show({ mode: "recovery-code" });

    expect(recoveryField()).not.toHaveAttribute("maxlength");
  });
});

describe("SecondFactorForm: the focus", () => {
  it("SS64: the field takes the focus when the person changed the mode", () => {
    show({ mode: "recovery-code", focusField: true });

    expect(recoveryField()).toHaveFocus();
  });

  it("SS32: on arrival the heading keeps the focus", () => {
    show();

    expect(screen.getByRole("heading", { level: 1 })).toHaveFocus();
  });
});

describe("SecondFactorForm: the controls", () => {
  it("SS64: the change of mode and the way back report the activation", async () => {
    const user = userEvent.setup();
    const onToggleMode = vi.fn();
    const onBack = vi.fn();
    const onSubmit = vi.fn();
    show({ onToggleMode, onBack, onSubmit });

    await user.click(screen.getByRole("button", { name: "Usar un código de recuperación" }));
    await user.click(screen.getByRole("button", { name: "Volver al inicio de sesión" }));

    expect(onToggleMode).toHaveBeenCalledTimes(1);
    expect(onBack).toHaveBeenCalledTimes(1);
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("a submit with Enter in the field reports once", async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn((event: { preventDefault: () => void }) => {
      event.preventDefault();
    });
    show({ onSubmit });

    await user.type(codeField(), "{Enter}");

    expect(onSubmit).toHaveBeenCalledTimes(1);
  });
});

describe("SecondFactorForm: its states", () => {
  it("SS63: with a field error shows the summary with its link and marks the field", () => {
    show({
      fieldErrors: { code: "Este dato no es válido." },
      summary: [{ controlId: "second-factor-code", message: "Este dato no es válido." }],
    });

    expect(screen.getByRole("group", { name: "Hay 1 campo con error." })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Este dato no es válido." })).toBeInTheDocument();
    expect(codeField()).toHaveAttribute("aria-invalid", "true");
    expect(codeField()).toHaveAccessibleDescription(/Este dato no es válido\./);
  });

  it("SS67: a general error is a destructive alert, before the field", () => {
    show({ alert: { message: "El código de verificación no es correcto o ya expiró. Genere uno nuevo en su aplicación." } });

    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("Genere uno nuevo en su aplicación.");
    expect(alert.compareDocumentPosition(codeField()) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it("AW54: an alert with a trace id shows it and the control that copies it", () => {
    show({ alert: { message: "Ocurrió un error inesperado.", traceId: "01J8Z4K2QF9P0X" } });

    expect(screen.getByRole("alert")).toHaveTextContent("01J8Z4K2QF9P0X");
    expect(screen.getByRole("button", { name: "Copiar identificador" })).toBeInTheDocument();
  });

  it("SS39: while sending, the button is busy, still enabled and holds a spinner, and the polite region carries the wait", () => {
    show({ submitting: true, liveMessage: "Verificando el código. Esto puede tardar hasta 30 segundos." });

    const button = screen.getByRole("button", { name: /Verificar/ });
    expect(button).toHaveAttribute("aria-busy", "true");
    expect(button).toBeEnabled();
    expect(within(button).getByRole("status")).toBeInTheDocument();
    expect(screen.getByText("Verificando el código. Esto puede tardar hasta 30 segundos.")).toHaveAttribute("aria-live", "polite");
  });

  it("SS43: a count after a 429 is drawn outside every active region", () => {
    show({
      alert: { message: "Hizo demasiadas solicitudes seguidas. Espere unos segundos e intente de nuevo." },
      countdown: { remaining: "Puede intentar de nuevo en 15 s." },
    });

    const count = screen.getByText("Puede intentar de nuevo en 15 s.");
    expect(count.closest('[role="alert"], [role="status"], [aria-live]')).toBeNull();
  });
});
