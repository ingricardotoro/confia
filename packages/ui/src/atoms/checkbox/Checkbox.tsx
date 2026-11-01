import { Check, Minus } from "lucide-react";
import { Checkbox as CheckboxPrimitive } from "radix-ui";
import type { ComponentProps } from "react";
import { choiceControl } from "../../lib/choice-control";
import { cn } from "../../lib/cn";

/** `true`, `false` or `"indeterminate"`, the three states a checkbox announces. */
export type CheckedState = boolean | "indeterminate";

// A Radix checkbox: a button with role checkbox that the space bar toggles and that announces
// aria-checked="mixed" when indeterminate. The name comes from a Label (htmlFor) or aria-label.
// Both drawings are in the DOM and CSS shows the one that matches the state, so the control keeps
// working uncontrolled. They are decorative: the state is what is announced.
export type CheckboxProps = Omit<ComponentProps<typeof CheckboxPrimitive.Root>, "checked" | "defaultChecked" | "onCheckedChange"> & {
  checked?: CheckedState;
  defaultChecked?: CheckedState;
  onCheckedChange?: (checked: CheckedState) => void;
};

export function Checkbox({ className, ...rest }: CheckboxProps) {
  return (
    <CheckboxPrimitive.Root
      {...rest}
      className={cn(
        choiceControl,
        "rounded-sm",
        "data-[state=checked]:bg-(--choice-checked-bg) not-aria-invalid:data-[state=checked]:border-(--choice-checked-bg)",
        "data-[state=indeterminate]:bg-(--choice-checked-bg) not-aria-invalid:data-[state=indeterminate]:border-(--choice-checked-bg)",
        className,
      )}
    >
      <CheckboxPrimitive.Indicator className="flex items-center justify-center text-(--choice-checked-fg)">
        <Check className="size-4 group-data-[state=indeterminate]:hidden" />
        <Minus className="hidden size-4 group-data-[state=indeterminate]:block" />
      </CheckboxPrimitive.Indicator>
    </CheckboxPrimitive.Root>
  );
}
