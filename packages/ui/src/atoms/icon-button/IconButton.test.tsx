import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Plus } from "lucide-react";
import { describe, expect, it, vi } from "vitest";
import { renderWithI18n } from "../../test/render";
import { IconButton } from "./IconButton";

describe("IconButton", () => {
  it("DS15: is named by its mandatory label and the drawing is hidden from assistive technology", () => {
    const { container } = renderWithI18n(
      <IconButton label="Agregar cargo">
        <Plus />
      </IconButton>,
    );

    expect(screen.getByRole("button", { name: "Agregar cargo" })).toHaveAttribute("type", "button");
    expect(container.querySelector("svg")).toHaveAttribute("aria-hidden", "true");
  });

  it.each([
    ["sm", "size-8"],
    ["md", "size-10"],
    ["lg", "size-11"],
  ] as const)("is square at the %s size (%s)", (size, side) => {
    renderWithI18n(
      <IconButton label="Agregar cargo" size={size}>
        <Plus />
      </IconButton>,
    );

    const button = screen.getByRole("button", { name: "Agregar cargo" });
    expect(button).toHaveClass(side);
    expect(button.className).not.toMatch(/(?:^| )h-(?:8|10|11)(?: |$)/);
  });

  it("takes the variants of Button", () => {
    renderWithI18n(
      <IconButton label="Agregar cargo" variant="destructive">
        <Plus />
      </IconButton>,
    );

    expect(screen.getByRole("button", { name: "Agregar cargo" }).className).toContain("bg-(--button-destructive-bg)");
  });

  it("DS16: shows the focus ring when reached with the keyboard", async () => {
    const user = userEvent.setup();
    renderWithI18n(
      <IconButton label="Agregar cargo">
        <Plus />
      </IconButton>,
    );

    await user.tab();

    const button = screen.getByRole("button", { name: "Agregar cargo" });
    expect(button).toHaveFocus();
    expect(button).toHaveClass("focus-visible:outline-border-focus");
    expect(button.className).not.toContain("outline-none");
  });

  it("DS17: while loading it keeps its name, stays focusable, replaces the icon with a Spinner and ignores activation", async () => {
    const user = userEvent.setup();
    const onClick = vi.fn();
    const { container } = renderWithI18n(
      <IconButton label="Agregar cargo" loading onClick={onClick}>
        <Plus />
      </IconButton>,
    );

    await user.tab();
    await user.keyboard("{Enter}");
    await user.click(screen.getByRole("button", { name: /Agregar cargo/ }));

    const button = screen.getByRole("button", { name: /Agregar cargo/ });
    expect(button).toHaveFocus();
    expect(button).toHaveAttribute("aria-busy", "true");
    expect(button).toBeEnabled();
    expect(screen.getByRole("status", { name: "Cargando" })).toBeInTheDocument();
    expect(container.querySelectorAll("svg")).toHaveLength(1);
    expect(onClick).not.toHaveBeenCalled();
  });

  it("calls the handler when activated", async () => {
    const user = userEvent.setup();
    const onClick = vi.fn();
    renderWithI18n(
      <IconButton label="Agregar cargo" onClick={onClick}>
        <Plus />
      </IconButton>,
    );

    await user.click(screen.getByRole("button", { name: "Agregar cargo" }));

    expect(onClick).toHaveBeenCalledTimes(1);
  });
});
