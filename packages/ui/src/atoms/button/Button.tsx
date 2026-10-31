import { cva, type VariantProps } from "class-variance-authority";
import type { ComponentProps, MouseEvent } from "react";
import { cn } from "../../lib/cn";
import { focusRing } from "../../lib/focus-ring";
import { Spinner } from "../spinner";

// docs/ui-ux/02 section 4: variants primary, secondary, subtle, destructive and link. Colors come
// from the Level 3 tokens of components.css (--button-*), never from a primitive. Heights are the
// dense admin target (32 px), the default (40 px) and the portal touch target (44 px).
export const buttonVariants = cva(
  [
    "inline-flex shrink-0 items-center justify-center gap-2 rounded-md font-medium whitespace-nowrap",
    "transition-colors duration-(--duration-base) ease-standard",
    "[&_svg:not([class*='size-'])]:size-4 [&_svg]:shrink-0",
    "disabled:cursor-not-allowed disabled:opacity-50 aria-busy:cursor-progress",
    focusRing,
  ],
  {
    variants: {
      variant: {
        primary:
          "bg-(--button-primary-bg) text-(--button-primary-fg) hover:bg-(--button-primary-bg-hover) active:bg-(--button-primary-bg-active)",
        secondary:
          "border border-(--button-secondary-border) bg-(--button-secondary-bg) text-(--button-secondary-fg) hover:bg-(--button-secondary-bg-hover) active:bg-(--button-secondary-bg-active)",
        subtle:
          "bg-transparent text-(--button-subtle-fg) hover:bg-(--button-subtle-bg-hover) active:bg-(--button-subtle-bg-active)",
        destructive:
          "bg-(--button-destructive-bg) text-(--button-destructive-fg) hover:brightness-90 active:brightness-75",
        link: "bg-transparent text-text-link underline underline-offset-4 hover:brightness-90",
      },
      size: {
        sm: "h-8 px-3 text-sm",
        md: "h-10 px-4 text-sm",
        lg: "h-11 px-6 text-base",
      },
    },
    defaultVariants: { variant: "primary", size: "md" },
  },
);

export interface ButtonProps extends ComponentProps<"button">, VariantProps<typeof buttonVariants> {
  /**
   * An action is in progress. The button stays focusable and announces itself as busy, but it does
   * not call its handler again (docs/ui-ux/04, financial writes): `disabled` would drop the focus
   * and hide the state from a keyboard user.
   */
  loading?: boolean;
}

export function Button({
  variant,
  size,
  loading = false,
  type = "button",
  className,
  onClick,
  children,
  ...rest
}: ButtonProps) {
  function handleClick(event: MouseEvent<HTMLButtonElement>) {
    if (loading) {
      // Also stops a submit button from submitting its form a second time.
      event.preventDefault();
      return;
    }
    onClick?.(event);
  }

  return (
    <button
      {...rest}
      type={type}
      aria-busy={loading || undefined}
      onClick={handleClick}
      className={cn(buttonVariants({ variant, size }), className)}
    >
      {loading ? <Spinner size="sm" className="text-current" /> : null}
      {children}
    </button>
  );
}
