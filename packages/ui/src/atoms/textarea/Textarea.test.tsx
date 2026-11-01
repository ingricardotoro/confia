import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { renderWithI18n } from "../../test/render";
import { Textarea } from "./Textarea";

describe("Textarea", () => {
  it("is a text box with the accessible name of its label, rendered in the real DOM order", () => {
    renderWithI18n(
      <>
        <label htmlFor="notes">Observaciones</label>
        <Textarea id="notes" />
      </>,
    );

    expect(screen.getByRole("textbox", { name: "Observaciones" })).toBeInstanceOf(HTMLTextAreaElement);
  });

  it("accepts typing, including a line break", async () => {
    const user = userEvent.setup();
    renderWithI18n(<Textarea aria-label="Observaciones" />);

    await user.type(screen.getByRole("textbox", { name: "Observaciones" }), "uno{Enter}dos");

    expect(screen.getByRole("textbox", { name: "Observaciones" })).toHaveValue("uno\ndos");
  });

  it("forwards the native attributes it does not own", () => {
    renderWithI18n(<Textarea aria-label="Observaciones" rows={6} maxLength={200} />);

    const textarea = screen.getByRole("textbox", { name: "Observaciones" });
    expect(textarea).toHaveAttribute("rows", "6");
    expect(textarea).toHaveAttribute("maxlength", "200");
  });

  describe("DS18: read-only against disabled", () => {
    it("read-only is focusable with the keyboard, is not disabled, and does not accept typing", async () => {
      const user = userEvent.setup();
      renderWithI18n(<Textarea aria-label="Nota" readOnly defaultValue="Pago parcial" />);
      const textarea = screen.getByRole("textbox", { name: "Nota" });

      await user.tab();
      expect(textarea).toHaveFocus();
      expect(textarea).not.toBeDisabled();

      await user.keyboard("x");
      expect(textarea).toHaveValue("Pago parcial");
    });

    it("the value of a read-only textarea can be selected and copied", async () => {
      const user = userEvent.setup();
      renderWithI18n(<Textarea aria-label="Nota" readOnly defaultValue="Pago parcial" />);
      const textarea = screen.getByRole<HTMLTextAreaElement>("textbox", { name: "Nota" });

      await user.click(textarea);
      textarea.select();

      expect(textarea.selectionStart).toBe(0);
      expect(textarea.selectionEnd).toBe(12);
      const copied = await user.copy();
      expect(copied?.getData("text")).toBe("Pago parcial");
    });

    it("disabled cannot be focused", async () => {
      const user = userEvent.setup();
      renderWithI18n(<Textarea aria-label="Nota" disabled defaultValue="Pago parcial" />);
      const textarea = screen.getByRole("textbox", { name: "Nota" });

      await user.tab();

      expect(textarea).toBeDisabled();
      expect(textarea).not.toHaveFocus();
    });
  });

  it("DS16: shows the focus ring from the focus token when reached with the keyboard", async () => {
    const user = userEvent.setup();
    renderWithI18n(<Textarea aria-label="Observaciones" />);

    await user.tab();

    const textarea = screen.getByRole("textbox", { name: "Observaciones" });
    expect(textarea).toHaveFocus();
    expect(textarea).toHaveClass("focus-visible:outline-2", "focus-visible:outline-border-focus");
  });

  it("marks itself invalid only when it is told so", () => {
    const { rerender } = renderWithI18n(<Textarea aria-label="Observaciones" />);
    expect(screen.getByRole("textbox", { name: "Observaciones" })).not.toHaveAttribute("aria-invalid");

    rerender(<Textarea aria-label="Observaciones" aria-invalid="true" />);

    expect(screen.getByRole("textbox", { name: "Observaciones" })).toBeInvalid();
  });

  it("shares the field states of Input, so the two cannot drift apart", () => {
    renderWithI18n(<Textarea aria-label="Observaciones" />);

    expect(screen.getByRole("textbox", { name: "Observaciones" })).toHaveClass(
      "bg-(--input-bg)",
      "text-(--input-fg)",
      "border-(--input-border)",
      "read-only:not-disabled:bg-bg-canvas",
      "disabled:opacity-50",
      "aria-invalid:border-(--input-invalid-border)",
    );
  });

  it("merges the class of the caller over its own", () => {
    renderWithI18n(<Textarea aria-label="Observaciones" className="min-h-40" />);

    const textarea = screen.getByRole("textbox", { name: "Observaciones" });
    expect(textarea).toHaveClass("min-h-40");
    expect(textarea).not.toHaveClass("min-h-20");
  });
});
