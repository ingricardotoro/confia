import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactElement } from "react";
import { describe, expect, it } from "vitest";
import { Checkbox } from "../../atoms/checkbox";
import { Input } from "../../atoms/input";
import { RadioGroup, RadioGroupItem } from "../../atoms/radio-group";
import { Textarea } from "../../atoms/textarea";
import { renderWithI18n } from "../../test/render";
import { FormErrorSummary } from "../form-error-summary";
import { FormError as FormErrorFromIndex, FormField, type FormFieldProps } from "./index";

const MESSAGE = "Ingrese un monto mayor que cero";
const HINT = "Sin separador de miles";

type Fields = Omit<FormFieldProps, "label" | "children">;

// The four controls the field must wire, each given the association props by the field.
function inputField(fields: Fields = {}): ReactElement {
  return (
    <FormField label="Monto a cobrar" {...fields}>
      {(control) => <Input {...control} />}
    </FormField>
  );
}

const controls: [string, string, (fields?: Fields) => ReactElement][] = [
  ["Input", "textbox", inputField],
  [
    "Textarea",
    "textbox",
    (fields = {}) => (
      <FormField label="Monto a cobrar" {...fields}>
        {(control) => <Textarea {...control} />}
      </FormField>
    ),
  ],
  [
    "Checkbox",
    "checkbox",
    (fields = {}) => (
      <FormField label="Monto a cobrar" {...fields}>
        {(control) => <Checkbox {...control} />}
      </FormField>
    ),
  ],
  [
    "RadioGroup",
    "radiogroup",
    (fields = {}) => (
      <FormField label="Monto a cobrar" {...fields}>
        {(control) => (
          <RadioGroup {...control}>
            <RadioGroupItem value="a" aria-label="Efectivo" />
          </RadioGroup>
        )}
      </FormField>
    ),
  ],
];

describe("FormField", () => {
  describe.each(controls)("with a %s", (_name, role, field) => {
    it("DS20: the control is found by its accessible name, which is the text of the label", () => {
      renderWithI18n(field());

      expect(screen.getByRole(role, { name: "Monto a cobrar" })).toBeInTheDocument();
    });

    it("DS21: with an error the control is invalid and its description is the message, present as text", () => {
      renderWithI18n(field({ error: MESSAGE }));
      const control = screen.getByRole(role, { name: "Monto a cobrar" });

      expect(control).toHaveAttribute("aria-invalid", "true");
      const message = screen.getByText(MESSAGE);
      expect(control.getAttribute("aria-describedby")?.split(" ")).toContain(message.id);
      expect(control).toHaveAccessibleDescription(MESSAGE);
    });

    it("DS22: with a description and an error, the control is described by both", () => {
      renderWithI18n(field({ description: HINT, error: MESSAGE }));
      const control = screen.getByRole(role, { name: "Monto a cobrar" });

      expect(control.getAttribute("aria-describedby")?.split(" ")).toEqual([
        screen.getByText(HINT).id,
        screen.getByText(MESSAGE).id,
      ]);
      expect(control).toHaveAccessibleDescription(`${HINT} ${MESSAGE}`);
    });

    it("DS24: without an error it is not invalid and references no error element", () => {
      renderWithI18n(field({ description: HINT }));
      const control = screen.getByRole(role, { name: "Monto a cobrar" });

      expect(control).not.toHaveAttribute("aria-invalid");
      expect(control.getAttribute("aria-describedby")).toBe(screen.getByText(HINT).id);
      expect(control).toHaveAccessibleDescription(HINT);
    });

    it.each(["", "   "])("DS24: a description of %j is absent: no paragraph and no reference to it", (blank) => {
      renderWithI18n(field({ description: blank }));
      const control = screen.getByRole(role, { name: "Monto a cobrar" });

      expect(control).not.toHaveAttribute("aria-describedby");
      // No paragraph anywhere in the render, at any depth.
      expect(screen.queryAllByRole("paragraph")).toHaveLength(0);
    });

    it.each(["", "   "])("DS24: an error of %j is absent: not invalid, no paragraph and no reference", (blank) => {
      renderWithI18n(field({ error: blank }));
      const control = screen.getByRole(role, { name: "Monto a cobrar" });

      expect(control).not.toHaveAttribute("aria-invalid");
      expect(control).not.toHaveAttribute("aria-describedby");
      expect(screen.queryAllByRole("paragraph")).toHaveLength(0);
    });

    it("DS24: with a blank description and a real error, it references only the error", () => {
      renderWithI18n(field({ description: " ", error: MESSAGE }));
      const control = screen.getByRole(role, { name: "Monto a cobrar" });

      const ids = control.getAttribute("aria-describedby")?.split(" ") ?? [];
      expect(ids).toEqual([screen.getByText(MESSAGE).id]);
      for (const id of ids) expect(document.getElementById(id)?.textContent).not.toBe("");
      expect(control).toHaveAccessibleDescription(MESSAGE);
    });

    it("DS23: a required field says so in text and tells assistive technology", () => {
      renderWithI18n(field({ required: true }));
      const control = screen.getByRole(role, { name: /Monto a cobrar/ });

      expect(control).toHaveAttribute("aria-required", "true");
      expect(control).toHaveAccessibleName("Monto a cobrar Obligatorio");
      expect(screen.getByText("Obligatorio")).toBeVisible();
    });

    it("disables the control and dims the label that precedes it", () => {
      renderWithI18n(field({ disabled: true }));

      const control = screen.getByRole(role, { name: "Monto a cobrar" });
      // Radix marks a radio group with data-disabled and disables each of its options.
      expect(control).toHaveAttribute(role === "radiogroup" ? "data-disabled" : "disabled");
      if (role === "radiogroup") expect(screen.getByRole("radio", { name: "Efectivo" })).toBeDisabled();
      expect(screen.getByText("Monto a cobrar")).toHaveAttribute("data-disabled", "true");
    });
  });

  it("DS24: with neither description nor error it references nothing", () => {
    renderWithI18n(inputField());

    expect(screen.getByRole("textbox")).not.toHaveAttribute("aria-describedby");
    expect(screen.getByRole("textbox")).not.toHaveAttribute("aria-invalid");
    expect(screen.getByRole("textbox")).not.toHaveAttribute("aria-required");
  });

  it("DS24: an empty error string is no error at all", () => {
    renderWithI18n(inputField({ error: "" }));

    expect(screen.getByRole("textbox")).not.toHaveAttribute("aria-invalid");
    expect(screen.getByRole("textbox")).not.toHaveAttribute("aria-describedby");
  });

  it("DS24: when the error goes away, the invalid state and its reference go with it", () => {
    const { rerender } = renderWithI18n(inputField({ error: MESSAGE }));
    expect(screen.getByRole("textbox")).toBeInvalid();

    rerender(inputField());

    expect(screen.getByRole("textbox")).not.toHaveAttribute("aria-invalid");
    expect(screen.queryByText(MESSAGE)).not.toBeInTheDocument();
  });

  it("focuses the control when its label is clicked", async () => {
    const user = userEvent.setup();
    renderWithI18n(inputField());

    await user.click(screen.getByText("Monto a cobrar"));

    expect(screen.getByRole("textbox", { name: "Monto a cobrar" })).toHaveFocus();
  });

  it("gives every field its own identifiers", () => {
    renderWithI18n(
      <>
        <FormField label="Nombre" error={MESSAGE}>
          {(control) => <Input {...control} />}
        </FormField>
        <FormField label="Apellido" error={MESSAGE}>
          {(control) => <Input {...control} />}
        </FormField>
      </>,
    );

    const [first, second] = screen.getAllByRole("textbox");
    expect(first?.id).not.toBe(second?.id);
    expect(first?.getAttribute("aria-describedby")).not.toBe(second?.getAttribute("aria-describedby"));
    expect(screen.getByRole("textbox", { name: "Nombre" })).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Apellido" })).toBeInTheDocument();
  });

  it("merges the class of the caller onto the wrapper", () => {
    const { container } = renderWithI18n(inputField({ className: "mt-4" }));

    expect(container.firstElementChild).toHaveClass("mt-4");
  });

  it("exports FormError from its own index, as text with the identifier it is given", () => {
    renderWithI18n(<FormErrorFromIndex id="problem">{MESSAGE}</FormErrorFromIndex>);

    expect(screen.getByText(MESSAGE)).toHaveAttribute("id", "problem");
  });

  it("DS98: controlId reaches the control and its label, and the summary link focuses it", async () => {
    const user = userEvent.setup();
    renderWithI18n(
      <>
        <FormErrorSummary errors={[{ controlId: "identifier", message: "Ingrese el identificador." }]} />
        <FormField label="Identificador" controlId="identifier">
          {(control) => <Input {...control} />}
        </FormField>
        <FormField label="Otro campo">{(control) => <Input {...control} />}</FormField>
      </>,
    );
    const control = screen.getByRole("textbox", { name: "Identificador" });
    const label = screen.getByText("Identificador", { selector: "label" });

    expect(control).toHaveAttribute("id", "identifier");
    expect(label).toHaveAttribute("for", "identifier");

    await user.click(screen.getByRole("link", { name: "Ingrese el identificador." }));
    expect(control).toHaveFocus();

    const other = screen.getByRole("textbox", { name: "Otro campo" });
    expect(other.id).not.toBe("");
    expect(other.id).not.toBe("identifier");
    expect(screen.getByText("Otro campo", { selector: "label" })).toHaveAttribute("for", other.id);
  });

  it("DS98: controlId does not leak onto the wrapper", () => {
    const { container } = renderWithI18n(inputField({ controlId: "identifier" }));

    expect(container.firstElementChild).not.toHaveAttribute("controlid");
  });
});
