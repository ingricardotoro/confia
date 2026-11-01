import { fireEvent, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { renderWithI18n } from "../../test/render";
import { Button, type ButtonProps } from "./Button";

const variants = ["primary", "secondary", "subtle", "destructive", "link"] as const;

function classesOf(variant: NonNullable<ButtonProps["variant"]>): string {
  const { unmount } = renderWithI18n(<Button variant={variant}>Guardar</Button>);
  const classes = screen.getByRole("button", { name: "Guardar" }).className;
  unmount();
  return classes;
}

describe("Button", () => {
  it("is a button named by its content and never submits a form by accident", () => {
    renderWithI18n(<Button>Guardar</Button>);

    expect(screen.getByRole("button", { name: "Guardar" })).toHaveAttribute("type", "button");
  });

  it("lets the caller ask for a submit button", () => {
    renderWithI18n(<Button type="submit">Guardar</Button>);

    expect(screen.getByRole("button", { name: "Guardar" })).toHaveAttribute("type", "submit");
  });

  it("is the primary variant by default and reads the component tokens of Level 3", () => {
    renderWithI18n(<Button>Guardar</Button>);

    const classes = screen.getByRole("button", { name: "Guardar" }).className;
    expect(classes).toContain("bg-(--button-primary-bg)");
    expect(classes).toContain("text-(--button-primary-fg)");
    expect(classes).toContain("hover:bg-(--button-primary-bg-hover)");
    expect(classes).toContain("active:bg-(--button-primary-bg-active)");
  });

  it.each(variants)("renders the %s variant with a look of its own", (variant) => {
    const own = classesOf(variant);

    for (const other of variants.filter((v) => v !== variant)) {
      expect(own).not.toBe(classesOf(other));
    }
  });

  it("does not carry the primary background in any other variant", () => {
    for (const variant of variants.filter((v) => v !== "primary")) {
      expect(classesOf(variant)).not.toContain("bg-(--button-primary-bg)");
    }
  });

  it("DS04: never states a color as a primitive or a literal", () => {
    for (const variant of variants) {
      expect(classesOf(variant)).not.toMatch(/(?:bg|text|border)-(?:blue|red|slate|gray|white|black)\b|#[0-9a-f]{3,8}|\[#/i);
    }
  });

  it.each([
    ["sm", "h-8"],
    ["md", "h-10"],
    ["lg", "h-11"],
  ] as const)("keeps the %s size at the dense or touch target height %s", (size, height) => {
    renderWithI18n(<Button size={size}>Guardar</Button>);

    expect(screen.getByRole("button", { name: "Guardar" })).toHaveClass(height);
  });

  it("DS16: shows the focus ring from the focus token when reached with the keyboard", async () => {
    const user = userEvent.setup();
    renderWithI18n(<Button>Guardar</Button>);

    await user.tab();

    const button = screen.getByRole("button", { name: "Guardar" });
    expect(button).toHaveFocus();
    expect(button).toHaveClass("focus-visible:outline-2", "focus-visible:outline-border-focus");
    expect(button.className).not.toContain("outline-none");
  });

  it("calls the handler when activated", async () => {
    const user = userEvent.setup();
    const onClick = vi.fn();
    renderWithI18n(<Button onClick={onClick}>Guardar</Button>);

    await user.click(screen.getByRole("button", { name: "Guardar" }));

    expect(onClick).toHaveBeenCalledTimes(1);
  });

  describe("loading", () => {
    it("DS17: stays focusable, is busy, shows a Spinner and is not disabled", async () => {
      const user = userEvent.setup();
      renderWithI18n(<Button loading>Guardar</Button>);

      await user.tab();

      const button = screen.getByRole("button", { name: /Guardar/ });
      expect(button).toHaveFocus();
      expect(button).toHaveAttribute("aria-busy", "true");
      expect(button).not.toHaveAttribute("disabled");
      expect(button).toBeEnabled();
      expect(screen.getByRole("status", { name: "Cargando" })).toBeInTheDocument();
    });

    it("DS17: does not invoke the handler again from the mouse or the keyboard", async () => {
      const user = userEvent.setup();
      const onClick = vi.fn();
      renderWithI18n(
        <Button loading onClick={onClick}>
          Guardar
        </Button>,
      );

      const button = screen.getByRole("button", { name: /Guardar/ });
      await user.click(button);
      await user.keyboard("{Enter}");
      await user.keyboard(" ");
      fireEvent.click(button);

      expect(button).toHaveFocus();
      expect(onClick).not.toHaveBeenCalled();
    });

    it("does not submit the form of a submit button while it is loading", async () => {
      const user = userEvent.setup();
      const onSubmit = vi.fn((event: { preventDefault: () => void }) => {
        event.preventDefault();
      });
      renderWithI18n(
        <form onSubmit={onSubmit}>
          <Button type="submit" loading>
            Guardar
          </Button>
        </form>,
      );

      await user.click(screen.getByRole("button", { name: /Guardar/ }));
      await user.keyboard("{Enter}");

      expect(onSubmit).not.toHaveBeenCalled();
    });

    it("is not busy and has no Spinner when it is not loading", () => {
      renderWithI18n(<Button>Guardar</Button>);

      expect(screen.getByRole("button", { name: "Guardar" })).not.toHaveAttribute("aria-busy");
      expect(screen.queryByRole("status")).not.toBeInTheDocument();
    });

    it("names the Spinner from the catalog, not from the component", () => {
      renderWithI18n(<Button loading>Guardar</Button>);

      expect(screen.getByRole("status")).toHaveAccessibleName("Cargando");
    });
  });

  describe("disabled", () => {
    it("is really disabled: it cannot be focused and ignores activation", async () => {
      const user = userEvent.setup();
      const onClick = vi.fn();
      renderWithI18n(
        <Button disabled onClick={onClick}>
          Guardar
        </Button>,
      );

      await user.tab();
      await user.click(screen.getByRole("button", { name: "Guardar" }));

      const button = screen.getByRole("button", { name: "Guardar" });
      expect(button).toBeDisabled();
      expect(button).not.toHaveFocus();
      expect(onClick).not.toHaveBeenCalled();
    });

    it("shows the not-allowed cursor and a reduced opacity", () => {
      renderWithI18n(<Button disabled>Guardar</Button>);

      expect(screen.getByRole("button", { name: "Guardar" })).toHaveClass("disabled:cursor-not-allowed", "disabled:opacity-50");
    });
  });

  it("merges the class of the caller over its own", () => {
    renderWithI18n(<Button className="h-12">Guardar</Button>);

    const button = screen.getByRole("button", { name: "Guardar" });
    expect(button).toHaveClass("h-12");
    expect(button).not.toHaveClass("h-10");
  });
});
