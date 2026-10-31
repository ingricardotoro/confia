import type { ComponentProps } from "react";
import { cn } from "../../lib/cn";
import { fieldState } from "../../lib/field-state";

// A native input. Its states (focus, read-only, disabled, invalid) come from the shared field-state
// utility; `aria-invalid` is passed by the caller (FormField in 5.2 does it) and is what draws the
// error border, so the visual state can never disagree with the announced one.
export type InputProps = ComponentProps<"input">;

export function Input({ className, type = "text", ...rest }: InputProps) {
  return <input {...rest} type={type} className={cn(fieldState, "h-10 px-3 text-sm", className)} />;
}
