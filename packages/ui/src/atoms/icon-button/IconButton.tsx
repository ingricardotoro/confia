import { cva } from "class-variance-authority";
import type { ReactNode } from "react";
import { cn } from "../../lib/cn";
import { Button, type ButtonProps } from "../button";

const square = cva("px-0", {
  variants: { size: { sm: "size-8", md: "size-10", lg: "size-11" } },
  defaultVariants: { size: "md" },
});

export interface IconButtonProps extends Omit<ButtonProps, "aria-label" | "aria-labelledby" | "children"> {
  /**
   * The accessible name. Required on purpose (ADR-0018): a button that shows only a drawing has no
   * other name, and `aria-label` and `aria-labelledby` are not accepted so it cannot be bypassed.
   */
  label: string;
  /** The icon. Decorative: the label names the button. */
  children: ReactNode;
}

export function IconButton({ label, size, loading = false, className, children, ...rest }: IconButtonProps) {
  return (
    <Button {...rest} aria-label={label} size={size} loading={loading} className={cn(square({ size }), className)}>
      {loading ? null : children}
    </Button>
  );
}
