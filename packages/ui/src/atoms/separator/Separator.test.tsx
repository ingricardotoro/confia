import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { renderWithI18n } from "../../test/render";
import { Separator } from "./Separator";

describe("Separator", () => {
  it("is decorative by default: it has no role, so assistive technology skips it", () => {
    const { container } = renderWithI18n(<Separator />);

    expect(container.firstElementChild).toHaveAttribute("role", "none");
    expect(screen.queryByRole("separator")).not.toBeInTheDocument();
  });

  it("is a semantic separator, horizontal by default, when it is not decorative", () => {
    renderWithI18n(<Separator decorative={false} />);

    expect(screen.getByRole("separator")).toHaveAttribute("aria-orientation", "horizontal");
  });

  it("announces its vertical orientation when it is semantic", () => {
    renderWithI18n(<Separator decorative={false} orientation="vertical" />);

    expect(screen.getByRole("separator")).toHaveAttribute("aria-orientation", "vertical");
  });

  it("does not announce an orientation when it is decorative", () => {
    const { container } = renderWithI18n(<Separator orientation="vertical" />);

    expect(container.firstElementChild).not.toHaveAttribute("aria-orientation");
  });

  it("draws a thin line from the border token, along the axis of its orientation", () => {
    const { container, rerender } = renderWithI18n(<Separator />);
    expect(container.firstElementChild).toHaveClass("bg-border-subtle", "h-px", "w-full");

    rerender(<Separator orientation="vertical" />);
    expect(container.firstElementChild).toHaveClass("bg-border-subtle", "h-full", "w-px");
  });

  it("merges the class of the caller over its own", () => {
    const { container } = renderWithI18n(<Separator className="bg-border-interactive" />);

    expect(container.firstElementChild).toHaveClass("bg-border-interactive");
    expect(container.firstElementChild).not.toHaveClass("bg-border-subtle");
  });
});
