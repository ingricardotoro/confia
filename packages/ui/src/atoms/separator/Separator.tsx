import type { ComponentProps } from "react";
import { cn } from "../../lib/cn";

export interface SeparatorProps extends Omit<ComponentProps<"div">, "role" | "aria-orientation" | "children"> {
  orientation?: "horizontal" | "vertical";
  /**
   * A decorative separator only draws a line and is hidden from assistive technology (`role="none"`).
   * Pass `false` when the line divides content a screen reader user should perceive as separate.
   */
  decorative?: boolean;
}

export function Separator({ orientation = "horizontal", decorative = true, className, ...rest }: SeparatorProps) {
  return (
    <div
      {...rest}
      role={decorative ? "none" : "separator"}
      aria-orientation={decorative ? undefined : orientation}
      className={cn("shrink-0 bg-border-subtle", orientation === "horizontal" ? "h-px w-full" : "h-full w-px", className)}
    />
  );
}
