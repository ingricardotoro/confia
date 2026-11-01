import type { ComponentProps } from "react";
import { cn } from "../../lib/cn";
import { fieldState } from "../../lib/field-state";

// A native textarea that wears the same states as Input (focus, read-only, disabled, invalid) from
// the shared field-state utility. `aria-invalid` is passed by the caller and is what draws the
// error border.
export type TextareaProps = ComponentProps<"textarea">;

export function Textarea({ className, ...rest }: TextareaProps) {
  return <textarea {...rest} className={cn(fieldState, "min-h-20 resize-y px-3 py-2 text-sm", className)} />;
}
