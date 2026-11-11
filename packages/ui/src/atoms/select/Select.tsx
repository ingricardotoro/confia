import { ChevronDown } from "lucide-react";
import type { ComponentProps } from "react";
import { cn } from "../../lib/cn";
import { fieldState } from "../../lib/field-state";

// A native select, on purpose: the browser gives the keyboard model, the mobile picker and the
// screen-reader semantics for free, which suits the short lists of docs/ui-ux/02 (up to about
// fifteen options). It takes the same field states as Input, so FormField spreads its association
// props on it the same way. The chevron is decorative and does not take the pointer.
export type SelectProps = ComponentProps<"select">;

export function Select({ className, children, ...rest }: SelectProps) {
  return (
    <div className="relative">
      <select {...rest} className={cn(fieldState, "h-10 appearance-none py-0 pr-9 pl-3 text-sm", className)}>
        {children}
      </select>
      <ChevronDown className="pointer-events-none absolute top-1/2 right-3 size-4 -translate-y-1/2 text-text-secondary" />
    </div>
  );
}
