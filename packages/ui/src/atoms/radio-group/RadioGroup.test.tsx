import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { renderWithI18n } from "../../test/render";
import { Label } from "../label";
import { RadioGroup, RadioGroupItem, type RadioGroupProps } from "./RadioGroup";

type User = ReturnType<typeof userEvent.setup>;

// A key press that lasts one tick, like a real one. Radix moves the focus to the next option in a
// timeout and only selects it if the arrow key is still down when the focus arrives; user-event
// releases the key in the same turn, so a plain keyboard("{ArrowDown}") would move the focus and
// never select, which no keyboard does.
async function press(user: User, key: "ArrowDown" | "ArrowUp"): Promise<void> {
  await user.keyboard(`{${key}>}`);
  await user.keyboard(`{/${key}}`);
}

const OPTIONS = [
  { value: "cash", text: "Efectivo" },
  { value: "card", text: "Tarjeta" },
  { value: "transfer", text: "Transferencia" },
] as const;

// Each option is a label followed by its control, the order the forms use. The group is named by
// aria-label, the way a screen reader announces it.
function Methods({ disabledOption, ...props }: RadioGroupProps & { disabledOption?: string }) {
  return (
    <RadioGroup aria-label="Método de pago" {...props}>
      {OPTIONS.map(({ value, text }) => (
        <div key={value}>
          <Label htmlFor={`method-${value}`}>{text}</Label>
          <RadioGroupItem id={`method-${value}`} value={value} disabled={value === disabledOption} />
        </div>
      ))}
    </RadioGroup>
  );
}

describe("RadioGroup", () => {
  it("DS19: is a group with its name and three options, each announced with its label", () => {
    renderWithI18n(<Methods />);

    expect(screen.getByRole("radiogroup", { name: "Método de pago" })).toBeInTheDocument();
    expect(screen.getAllByRole("radio")).toHaveLength(3);
    for (const { text } of OPTIONS) {
      expect(screen.getByRole("radio", { name: text })).not.toBeChecked();
    }
  });

  it("DS19: the arrow keys move the selection, wrap around at both ends, and report each change", async () => {
    const user = userEvent.setup();
    const onValueChange = vi.fn();
    renderWithI18n(<Methods defaultValue="cash" onValueChange={onValueChange} />);

    await user.tab();
    expect(screen.getByRole("radio", { name: "Efectivo" })).toHaveFocus();

    await press(user, "ArrowDown");
    expect(screen.getByRole("radio", { name: "Tarjeta" })).toBeChecked();
    expect(screen.getByRole("radio", { name: "Tarjeta" })).toHaveFocus();

    await press(user, "ArrowDown");
    await press(user, "ArrowDown");
    expect(screen.getByRole("radio", { name: "Efectivo" })).toBeChecked();

    await press(user, "ArrowUp");
    expect(screen.getByRole("radio", { name: "Transferencia" })).toBeChecked();

    expect(onValueChange.mock.calls).toEqual([["card"], ["transfer"], ["cash"], ["transfer"]]);
  });

  it("DS19: the selected option is the one tab stop, so one Tab enters, the next one leaves and Shift+Tab returns to it", async () => {
    const user = userEvent.setup();
    renderWithI18n(
      <>
        <Methods defaultValue="card" />
        <button type="button">Siguiente</button>
      </>,
    );

    await user.tab();
    expect(screen.getByRole("radio", { name: "Tarjeta" })).toHaveFocus();
    expect(screen.getByRole("radio", { name: "Tarjeta" })).toHaveAttribute("tabindex", "0");
    expect(screen.getByRole("radio", { name: "Efectivo" })).toHaveAttribute("tabindex", "-1");
    expect(screen.getByRole("radio", { name: "Transferencia" })).toHaveAttribute("tabindex", "-1");

    await user.tab();
    expect(screen.getByRole("button", { name: "Siguiente" })).toHaveFocus();

    await user.tab({ shift: true });
    expect(screen.getByRole("radio", { name: "Tarjeta" })).toHaveFocus();
  });

  it("enters on the first option when nothing is selected, and the space bar selects it", async () => {
    const user = userEvent.setup();
    renderWithI18n(<Methods />);

    await user.tab();
    expect(screen.getByRole("radio", { name: "Efectivo" })).toHaveFocus();

    await user.keyboard(" ");
    expect(screen.getByRole("radio", { name: "Efectivo" })).toBeChecked();
  });

  it("selects an option when its label is clicked", async () => {
    const user = userEvent.setup();
    renderWithI18n(<Methods />);

    await user.click(screen.getByText("Transferencia"));

    expect(screen.getByRole("radio", { name: "Transferencia" })).toBeChecked();
  });

  describe("disabled", () => {
    it("a disabled group has no focusable option and ignores the keyboard", async () => {
      const user = userEvent.setup();
      const onValueChange = vi.fn();
      renderWithI18n(<Methods disabled defaultValue="cash" onValueChange={onValueChange} />);

      await user.tab();
      await press(user, "ArrowDown");

      for (const radio of screen.getAllByRole("radio")) {
        expect(radio).toBeDisabled();
        expect(radio).not.toHaveFocus();
      }
      expect(onValueChange).not.toHaveBeenCalled();
    });

    it("an arrow key skips a disabled option", async () => {
      const user = userEvent.setup();
      renderWithI18n(<Methods defaultValue="cash" disabledOption="card" />);

      await user.tab();
      await press(user, "ArrowDown");

      expect(screen.getByRole("radio", { name: "Transferencia" })).toBeChecked();
      expect(screen.getByRole("radio", { name: "Tarjeta" })).toBeDisabled();
    });
  });

  it("DS16: shows the focus ring from the focus token when an option is reached with the keyboard", async () => {
    const user = userEvent.setup();
    renderWithI18n(<Methods />);

    await user.tab();

    const radio = screen.getByRole("radio", { name: "Efectivo" });
    expect(radio).toHaveFocus();
    expect(radio).toHaveClass("focus-visible:outline-2", "focus-visible:outline-border-focus");
  });

  it("marks an option invalid only when it is told so", () => {
    renderWithI18n(
      <RadioGroup aria-label="Método de pago">
        <RadioGroupItem value="cash" aria-label="Efectivo" aria-invalid="true" />
        <RadioGroupItem value="card" aria-label="Tarjeta" />
      </RadioGroup>,
    );

    expect(screen.getByRole("radio", { name: "Efectivo" })).toBeInvalid();
    expect(screen.getByRole("radio", { name: "Tarjeta" })).not.toHaveAttribute("aria-invalid");
  });

  it("merges the class of the caller over its own", () => {
    renderWithI18n(
      <RadioGroup aria-label="Método de pago" className="gap-6">
        <RadioGroupItem value="cash" aria-label="Efectivo" className="mt-2" />
      </RadioGroup>,
    );

    expect(screen.getByRole("radiogroup")).toHaveClass("gap-6");
    expect(screen.getByRole("radio", { name: "Efectivo" })).toHaveClass("mt-2");
  });
});
