import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { renderWithI18n } from "../../test/render";
import { Input } from "./Input";

describe("Input", () => {
  it("is a text box with the accessible name of its label", () => {
    renderWithI18n(
      <>
        <label htmlFor="name">Nombre</label>
        <Input id="name" />
      </>,
    );

    expect(screen.getByRole("textbox", { name: "Nombre" })).toBeInTheDocument();
  });

  it("accepts typing", async () => {
    const user = userEvent.setup();
    renderWithI18n(<Input aria-label="Nombre" />);

    await user.type(screen.getByRole("textbox", { name: "Nombre" }), "Ana");

    expect(screen.getByRole("textbox", { name: "Nombre" })).toHaveValue("Ana");
  });

  it("forwards the type to the native element", () => {
    renderWithI18n(<Input aria-label="Correo" type="email" />);

    expect(screen.getByRole("textbox", { name: "Correo" })).toHaveAttribute("type", "email");
  });

  describe("DS18: read-only against disabled", () => {
    it("read-only is focusable with the keyboard, is not disabled, and does not accept typing", async () => {
      const user = userEvent.setup();
      renderWithI18n(<Input aria-label="Saldo" readOnly defaultValue="1500" />);
      const input = screen.getByRole("textbox", { name: "Saldo" });

      await user.tab();
      expect(input).toHaveFocus();
      expect(input).not.toBeDisabled();

      await user.keyboard("9");
      expect(input).toHaveValue("1500");
    });

    it("the value of a read-only input can be selected and copied", async () => {
      const user = userEvent.setup();
      renderWithI18n(<Input aria-label="Saldo" readOnly defaultValue="1500" />);
      const input = screen.getByRole<HTMLInputElement>("textbox", { name: "Saldo" });

      await user.click(input);
      input.select();

      expect(input.selectionStart).toBe(0);
      expect(input.selectionEnd).toBe(4);
      const copied = await user.copy();
      expect(copied?.getData("text")).toBe("1500");
    });

    it("disabled cannot be focused", async () => {
      const user = userEvent.setup();
      renderWithI18n(<Input aria-label="Saldo" disabled defaultValue="1500" />);
      const input = screen.getByRole("textbox", { name: "Saldo" });

      await user.tab();

      expect(input).toBeDisabled();
      expect(input).not.toHaveFocus();
    });

    it("looks different when it is read-only and when it is disabled", () => {
      renderWithI18n(<Input aria-label="Saldo" />);

      const classes = screen.getByRole("textbox", { name: "Saldo" }).className;
      expect(classes).toContain("read-only:not-disabled:bg-bg-canvas");
      expect(classes).toContain("disabled:cursor-not-allowed");
      expect(classes).toContain("disabled:opacity-50");
    });
  });

  it("DS16: shows the focus ring from the focus token when reached with the keyboard", async () => {
    const user = userEvent.setup();
    renderWithI18n(<Input aria-label="Nombre" />);

    await user.tab();

    const input = screen.getByRole("textbox", { name: "Nombre" });
    expect(input).toHaveFocus();
    expect(input).toHaveClass("focus-visible:outline-2", "focus-visible:outline-border-focus");
  });

  it("marks itself invalid only when it is told so, and then draws the error border", () => {
    const { rerender } = renderWithI18n(<Input aria-label="Nombre" />);
    expect(screen.getByRole("textbox", { name: "Nombre" })).not.toHaveAttribute("aria-invalid");

    rerender(<Input aria-label="Nombre" aria-invalid="true" />);
    const input = screen.getByRole("textbox", { name: "Nombre" });
    expect(input).toBeInvalid();
    expect(input.className).toContain("aria-invalid:border-(--input-invalid-border)");
  });

  it("reads its colors from component tokens of Level 3", () => {
    renderWithI18n(<Input aria-label="Nombre" />);

    expect(screen.getByRole("textbox", { name: "Nombre" })).toHaveClass(
      "bg-(--input-bg)",
      "text-(--input-fg)",
      "border-(--input-border)",
    );
  });

  it("merges the class of the caller over its own", () => {
    renderWithI18n(<Input aria-label="Nombre" className="h-8" />);

    const input = screen.getByRole("textbox", { name: "Nombre" });
    expect(input).toHaveClass("h-8");
    expect(input).not.toHaveClass("h-10");
  });
});
