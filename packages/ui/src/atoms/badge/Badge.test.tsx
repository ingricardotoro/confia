import { screen } from "@testing-library/react";
import { Info } from "lucide-react";
import { describe, expect, it } from "vitest";
import { renderWithI18n } from "../../test/render";
import { Badge, type BadgeProps } from "./Badge";

const variants = ["neutral", "primary", "outline"] as const;

function classesOf(variant: NonNullable<BadgeProps["variant"]>): string {
  const { unmount } = renderWithI18n(<Badge variant={variant}>Etiqueta</Badge>);
  const classes = screen.getByText("Etiqueta").className;
  unmount();
  return classes;
}

describe("Badge", () => {
  it("shows its text and is neutral by default", () => {
    const { unmount } = renderWithI18n(<Badge>Etiqueta</Badge>);
    const byDefault = screen.getByText("Etiqueta").className;
    unmount();

    expect(byDefault).toBe(classesOf("neutral"));
  });

  it.each(variants)("renders the %s variant with a look of its own", (variant) => {
    const own = classesOf(variant);

    for (const other of variants.filter((v) => v !== variant)) {
      expect(own).not.toBe(classesOf(other));
    }
  });

  it("reads the component tokens of Level 3", () => {
    expect(classesOf("neutral")).toContain("bg-(--badge-neutral-bg)");
    expect(classesOf("neutral")).toContain("text-(--badge-neutral-fg)");
    expect(classesOf("primary")).toContain("bg-(--badge-primary-bg)");
    expect(classesOf("primary")).toContain("text-(--badge-primary-fg)");
    expect(classesOf("outline")).toContain("border-(--badge-outline-border)");
  });

  it("DS04: never states a color as a primitive or a literal", () => {
    for (const variant of variants) {
      expect(classesOf(variant)).not.toMatch(/(?:bg|text|border)-(?:blue|red|green|slate|gray|white|black)\b|#[0-9a-f]{3,8}|\[#/i);
    }
  });

  it("never lets color be the only carrier: the text stays visible and exposed at every variant", () => {
    for (const variant of variants) {
      const { unmount } = renderWithI18n(<Badge variant={variant}>Etiqueta</Badge>);
      expect(screen.getByText("Etiqueta")).toBeVisible();
      expect(screen.getByText("Etiqueta")).not.toHaveAttribute("aria-hidden");
      unmount();
    }
  });

  it("hides the icon that accompanies the text from assistive technology", () => {
    const { container } = renderWithI18n(<Badge icon={<Info />}>Etiqueta</Badge>);

    expect(container.querySelector("svg")).toHaveAttribute("aria-hidden", "true");
    expect(screen.getByText("Etiqueta")).toBeInTheDocument();
  });

  it("renders no icon when none is given", () => {
    const { container } = renderWithI18n(<Badge>Etiqueta</Badge>);

    expect(container.querySelector("svg")).not.toBeInTheDocument();
  });

  it.each([
    ["sm", "h-5"],
    ["md", "h-6"],
  ] as const)("keeps the %s size at height %s", (size, height) => {
    renderWithI18n(<Badge size={size}>Etiqueta</Badge>);

    expect(screen.getByText("Etiqueta")).toHaveClass(height);
  });

  it("merges the class of the caller over its own, which is how StatusBadge supplies its tokens", () => {
    renderWithI18n(<Badge className="bg-(--status-badge-paid-bg)">Etiqueta</Badge>);

    const badge = screen.getByText("Etiqueta");
    expect(badge).toHaveClass("bg-(--status-badge-paid-bg)");
    expect(badge).not.toHaveClass("bg-(--badge-neutral-bg)");
  });
});
