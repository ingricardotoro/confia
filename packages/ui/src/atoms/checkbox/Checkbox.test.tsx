import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { renderWithI18n } from "../../test/render";
import { Label } from "../label";
import { Checkbox, type CheckedState } from "./Checkbox";

// A controlled owner, like the form that will use the control: the checkbox only reports the change
// and the owner decides what is checked. The value is also printed so the test reads the owner.
function Harness({ initial = false }: { initial?: CheckedState }) {
  const [checked, setChecked] = useState<CheckedState>(initial);
  return (
    <>
      <Label htmlFor="terms">Acepto los términos</Label>
      <Checkbox id="terms" checked={checked} onCheckedChange={setChecked} />
      <output>{String(checked)}</output>
    </>
  );
}

describe("Checkbox", () => {
  it("is a checkbox named by its label, rendered in the real DOM order", () => {
    renderWithI18n(
      <>
        <Label htmlFor="terms">Acepto los términos</Label>
        <Checkbox id="terms" />
      </>,
    );

    expect(screen.getByRole("checkbox", { name: "Acepto los términos" })).not.toBeChecked();
  });

  it("DS19: the space bar toggles it, and a second press toggles it back", async () => {
    const user = userEvent.setup();
    renderWithI18n(<Harness />);
    const checkbox = screen.getByRole("checkbox", { name: "Acepto los términos" });

    await user.tab();
    expect(checkbox).toHaveFocus();

    await user.keyboard(" ");
    expect(checkbox).toBeChecked();
    expect(screen.getByRole("status")).toHaveTextContent("true");

    await user.keyboard(" ");
    expect(checkbox).not.toBeChecked();
    expect(screen.getByRole("status")).toHaveTextContent("false");
  });

  it("reports each activation once, with a boolean", async () => {
    const user = userEvent.setup();
    const onCheckedChange = vi.fn();
    renderWithI18n(<Checkbox aria-label="Recordar" onCheckedChange={onCheckedChange} />);

    await user.click(screen.getByRole("checkbox", { name: "Recordar" }));

    expect(onCheckedChange).toHaveBeenCalledExactlyOnceWith(true);
  });

  it("toggles when its label is clicked", async () => {
    const user = userEvent.setup();
    renderWithI18n(<Harness />);

    await user.click(screen.getByText("Acepto los términos"));

    expect(screen.getByRole("checkbox", { name: "Acepto los términos" })).toBeChecked();
  });

  describe("indeterminate", () => {
    it("is announced as mixed, not as checked or unchecked", () => {
      renderWithI18n(<Checkbox aria-label="Todos" checked="indeterminate" />);

      const checkbox = screen.getByRole("checkbox", { name: "Todos" });
      expect(checkbox).toBePartiallyChecked();
      expect(checkbox).toHaveAttribute("aria-checked", "mixed");
    });

    it("becomes checked when the space bar is pressed", async () => {
      const user = userEvent.setup();
      renderWithI18n(<Harness initial="indeterminate" />);

      await user.tab();
      await user.keyboard(" ");

      expect(screen.getByRole("checkbox", { name: "Acepto los términos" })).toBeChecked();
    });
  });

  describe("disabled", () => {
    it("cannot be focused and does not toggle", async () => {
      const user = userEvent.setup();
      const onCheckedChange = vi.fn();
      renderWithI18n(<Checkbox aria-label="Recordar" disabled onCheckedChange={onCheckedChange} />);
      const checkbox = screen.getByRole("checkbox", { name: "Recordar" });

      await user.tab();
      await user.click(checkbox);

      expect(checkbox).toBeDisabled();
      expect(checkbox).not.toHaveFocus();
      expect(onCheckedChange).not.toHaveBeenCalled();
    });
  });

  it("DS16: shows the focus ring from the focus token when reached with the keyboard", async () => {
    const user = userEvent.setup();
    renderWithI18n(<Checkbox aria-label="Recordar" />);

    await user.tab();

    const checkbox = screen.getByRole("checkbox", { name: "Recordar" });
    expect(checkbox).toHaveFocus();
    expect(checkbox).toHaveClass("focus-visible:outline-2", "focus-visible:outline-border-focus");
  });

  it("marks itself invalid only when it is told so", () => {
    const { rerender } = renderWithI18n(<Checkbox aria-label="Recordar" />);
    expect(screen.getByRole("checkbox", { name: "Recordar" })).not.toHaveAttribute("aria-invalid");

    rerender(<Checkbox aria-label="Recordar" aria-invalid="true" />);

    expect(screen.getByRole("checkbox", { name: "Recordar" })).toBeInvalid();
  });

  it("keeps its drawing hidden from assistive technology, so only the state is announced", () => {
    const { container } = renderWithI18n(<Checkbox aria-label="Recordar" defaultChecked />);

    expect(container.querySelector("svg")).toHaveAttribute("aria-hidden", "true");
  });

  it("merges the class of the caller over its own", () => {
    renderWithI18n(<Checkbox aria-label="Recordar" className="mt-2" />);

    expect(screen.getByRole("checkbox", { name: "Recordar" })).toHaveClass("mt-2");
  });
});
