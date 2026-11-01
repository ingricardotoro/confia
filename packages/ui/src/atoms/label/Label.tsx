import type { ComponentProps } from "react";
import { cn } from "../../lib/cn";

// A native label: the association with its control is `htmlFor` (or nesting), which the browser
// already turns into the control's accessible name and into the click-to-focus behaviour.
//
// Dimming next to a disabled control works two ways. `peer-disabled` only matches a sibling that
// comes BEFORE the label in the DOM, so it serves the control-first order. In the usual
// label-then-control order CSS cannot look forward, so the owner of the pair (FormField in 5.2)
// passes `disabled`, which sets `data-disabled` and dims the label without relying on DOM order.
export interface LabelProps extends ComponentProps<"label"> {
  /** The control this label names is disabled. Dims the label whatever the DOM order. */
  disabled?: boolean;
}

export function Label({ className, disabled = false, ...rest }: LabelProps) {
  return (
    <label
      {...rest}
      data-disabled={disabled || undefined}
      className={cn(
        "text-sm font-medium text-text-primary select-none",
        "peer-disabled:cursor-not-allowed peer-disabled:opacity-70 data-disabled:cursor-not-allowed data-disabled:opacity-70",
        className,
      )}
    />
  );
}
