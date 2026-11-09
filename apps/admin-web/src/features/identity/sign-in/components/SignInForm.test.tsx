import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { renderWithAdminI18n } from "../../../../test/render.tsx";
import { SignInForm, type SignInFormProps } from "./SignInForm.tsx";

// admin-web-staff-sign-in, task 3.1 (E8; SS37, SS39, SS42, SS51, SS52, SS55). The sign in screen as a presentation.
// States. Success: the empty form, one with a general error, one with a trace id, one with field errors and its summary,
// one with an arrival notice, and one sending. Loading and empty: not applicable, it reads nothing; the request of the
// form is the "sending" state. The error of a request is the alert and the field errors, shown here from props.
const noop = () => undefined;
const base: SignInFormProps = {
  values: { identifier: "", password: "" },
  fieldErrors: {},
  summary: [],
  submitting: false,
  summaryRef: noop,
  alertRef: noop,
  onChange: noop,
  onBlur: noop,
  onSubmit: noop,
};

function show(props: Partial<SignInFormProps> = {}) {
  return renderWithAdminI18n(<SignInForm {...base} {...props} />);
}

const identifier = () => screen.getByRole("textbox", { name: /Correo electrónico/ });
const password = () => screen.getByLabelText(/Contraseña/, { selector: "input" });

describe("SignInForm: the fields", () => {
  it("AW57, SS52: has one level 1 heading and the two fields with a visible label and the autocomplete of each", () => {
    show();

    expect(screen.getByRole("heading", { level: 1, name: "Iniciar sesión" })).toBeInTheDocument();
    expect(identifier()).toHaveAttribute("autocomplete", "username");
    expect(password()).toHaveAttribute("type", "password");
    expect(password()).toHaveAttribute("autocomplete", "current-password");
    expect(screen.getAllByText("Obligatorio")).toHaveLength(2);
  });

  it("SS52: the identifier is plain text for the keyboard of an address, with no capital and no spell checking", () => {
    show();

    expect(identifier()).toHaveAttribute("type", "text");
    expect(identifier()).toHaveAttribute("inputmode", "email");
    expect(identifier()).toHaveAttribute("autocapitalize", "none");
    expect(identifier()).toHaveAttribute("spellcheck", "false");
  });

  it("SS51: no field has a maxlength or a pattern, and the form does not validate by itself", () => {
    show();

    for (const control of [identifier(), password()]) {
      expect(control).not.toHaveAttribute("maxlength");
      expect(control).not.toHaveAttribute("pattern");
    }
    expect(screen.getByRole("button", { name: "Iniciar sesión" }).closest("form")).toHaveAttribute("novalidate");
  });

  it("SS51: a pasted value longer than the contract maximum reaches the handler whole", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    function Pasting() {
      const [values, setValues] = useState(base.values);
      return (
        <SignInForm
          {...base}
          values={values}
          onChange={(field, value) => {
            onChange(field, value);
            setValues((previous) => ({ ...previous, [field]: value }));
          }}
        />
      );
    }
    renderWithAdminI18n(<Pasting />);
    const pasted = "a".repeat(400);

    await user.click(identifier());
    await user.paste(pasted);

    expect(onChange).toHaveBeenLastCalledWith("identifier", pasted);
    expect(identifier()).toHaveValue(pasted);
  });

  it("shows the values it is given and reports what is typed and when a field is left", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    const onBlur = vi.fn();
    show({ values: { identifier: "ana@colegio.edu.hn", password: "" }, onChange, onBlur });
    expect(identifier()).toHaveValue("ana@colegio.edu.hn");

    await user.type(password(), "x");
    await user.tab();

    expect(onChange).toHaveBeenCalledWith("password", "x");
    expect(onBlur).toHaveBeenCalledWith("password");
  });
});

describe("SignInForm: the contact text", () => {
  it("SS55: tells the person to contact the administration, with no link and no forgotten password control", () => {
    show();

    expect(
      screen.getByText("Si no recuerda su contraseña, comuníquese con la administración de su institución."),
    ).toBeInTheDocument();
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /olvid|recuper|restablec/i })).not.toBeInTheDocument();
  });
});

describe("SignInForm: its states", () => {
  it("the texts of the labels and of the buttons are the approved ones of design section 8", () => {
    show();

    expect(screen.getByText("Correo electrónico")).toBeInTheDocument();
    expect(screen.getByText("Contraseña")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Iniciar sesión" })).toHaveAttribute("type", "submit");
    expect(screen.getByRole("button", { name: "Mostrar contraseña" })).toBeInTheDocument();
  });

  it("SS39: while sending, the button is busy, still enabled and holds a spinner", () => {
    show({ submitting: true });

    // The spinner is part of the name of the button while it works.
    const button = screen.getByRole("button", { name: /Iniciar sesión/ });
    expect(button).toHaveAttribute("aria-busy", "true");
    expect(button).toBeEnabled();
    expect(within(button).getByRole("status")).toBeInTheDocument();
  });

  it("SS37: with field errors shows the summary with one link per error and marks each field", () => {
    show({
      fieldErrors: { identifier: "Este dato es obligatorio.", password: "Este dato es obligatorio." },
      summary: [
        { controlId: "sign-in-identifier", message: "Este dato es obligatorio." },
        { controlId: "sign-in-password", message: "Este dato es obligatorio." },
      ],
    });

    expect(screen.getByRole("group", { name: "Hay 2 campos con errores." })).toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: "Este dato es obligatorio." })).toHaveLength(2);
    expect(identifier()).toHaveAttribute("aria-invalid", "true");
    expect(password()).toHaveAttribute("aria-invalid", "true");
  });

  it("SS37: the summary comes before the fields, and no summary is shown without errors", () => {
    const view = show({ summary: [{ controlId: "sign-in-identifier", message: "Este dato es obligatorio." }] });
    const summary = screen.getByRole("group");

    expect(summary.compareDocumentPosition(identifier()) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    view.unmount();
    show();
    expect(screen.queryByRole("group")).not.toBeInTheDocument();
  });

  it("SS42: a general error is a destructive alert, before the fields", () => {
    show({ alert: { message: "El correo o la contraseña no son correctos." } });

    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("El correo o la contraseña no son correctos.");
    expect(alert.compareDocumentPosition(identifier()) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it("SS50: an alert with a trace id shows it and the control that copies it", () => {
    show({ alert: { message: "Ocurrió un error inesperado.", traceId: "01J8Z4K2QF9P0X" } });

    expect(screen.getByRole("alert")).toHaveTextContent("01J8Z4K2QF9P0X");
    expect(screen.getByRole("button", { name: "Copiar identificador" })).toBeInTheDocument();
  });

  it("an arrival notice is shown as information after the heading, and the heading keeps the focus", () => {
    show({ notice: "Su sesión se cerró por seguridad. Vuelva a iniciar sesión." });

    const notice = screen.getByRole("status");
    expect(notice).toHaveTextContent("Su sesión se cerró por seguridad. Vuelva a iniciar sesión.");
    expect(screen.getByRole("heading", { level: 1 })).toHaveFocus();
    expect(notice).not.toHaveFocus();
  });

  it("shows no notice and no alert when it is given none", () => {
    show();

    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });
});
