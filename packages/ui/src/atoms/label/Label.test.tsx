import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { renderWithI18n } from "../../test/render";
import { Label } from "./Label";

describe("Label", () => {
  it("is associated with its control through htmlFor and gives it its accessible name", () => {
    renderWithI18n(
      <>
        <Label htmlFor="amount">Monto a cobrar</Label>
        <input id="amount" />
      </>,
    );

    expect(screen.getByRole("textbox", { name: "Monto a cobrar" })).toBeInTheDocument();
    expect(screen.getByLabelText("Monto a cobrar")).toHaveAttribute("id", "amount");
  });

  it("moves the focus to its control when it is clicked", async () => {
    const user = userEvent.setup();
    renderWithI18n(
      <>
        <Label htmlFor="amount">Monto a cobrar</Label>
        <input id="amount" />
      </>,
    );

    await user.click(screen.getByText("Monto a cobrar"));

    expect(screen.getByRole("textbox", { name: "Monto a cobrar" })).toHaveFocus();
  });

  it("renders a label element and not a generic one", () => {
    const { container } = renderWithI18n(<Label htmlFor="amount">Monto a cobrar</Label>);

    expect(container.querySelector("label")).toHaveAttribute("for", "amount");
  });

  it("reads its colors from semantic tokens", () => {
    renderWithI18n(<Label htmlFor="amount">Monto a cobrar</Label>);

    expect(screen.getByText("Monto a cobrar")).toHaveClass("text-text-primary");
  });

  it("dims itself when the control that follows it is disabled", () => {
    renderWithI18n(<Label htmlFor="amount">Monto a cobrar</Label>);

    expect(screen.getByText("Monto a cobrar")).toHaveClass("peer-disabled:cursor-not-allowed", "peer-disabled:opacity-70");
  });

  it("merges the class of the caller over its own", () => {
    renderWithI18n(
      <Label htmlFor="amount" className="text-text-secondary">
        Monto a cobrar
      </Label>,
    );

    const label = screen.getByText("Monto a cobrar");
    expect(label).toHaveClass("text-text-secondary");
    expect(label).not.toHaveClass("text-text-primary");
  });
});
