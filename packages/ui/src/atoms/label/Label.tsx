import type { ComponentProps } from "react";
import { cn } from "../../lib/cn";

// A native label: the association with its control is `htmlFor` (or nesting), which the browser
// already turns into the control's accessible name and into the click-to-focus behaviour. The peer
// classes dim it when the control that precedes it in the DOM is disabled.
export type LabelProps = ComponentProps<"label">;

export function Label({ className, ...rest }: LabelProps) {
  return (
    <label
      {...rest}
      className={cn(
        "text-sm font-medium text-text-primary select-none peer-disabled:cursor-not-allowed peer-disabled:opacity-70",
        className,
      )}
    />
  );
}
