import { RadioGroup as RadioGroupPrimitive } from "radix-ui";
import type { ComponentProps } from "react";
import { choiceControl } from "../../lib/choice-control";
import { cn } from "../../lib/cn";

// A Radix radio group: role radiogroup, one tab stop (the selected option, or the first when none is
// selected) and the arrow keys moving the selection with wrap-around. The group is named with
// aria-label or aria-labelledby; each option is named by its Label (htmlFor) or aria-label.
export type RadioGroupProps = ComponentProps<typeof RadioGroupPrimitive.Root>;

export function RadioGroup({ className, ...rest }: RadioGroupProps) {
  return <RadioGroupPrimitive.Root {...rest} className={cn("grid gap-3", className)} />;
}

export type RadioGroupItemProps = ComponentProps<typeof RadioGroupPrimitive.Item>;

export function RadioGroupItem({ className, ...rest }: RadioGroupItemProps) {
  return (
    <RadioGroupPrimitive.Item
      {...rest}
      className={cn(choiceControl, "rounded-full", "not-aria-invalid:data-[state=checked]:border-(--choice-checked-bg)", className)}
    >
      <RadioGroupPrimitive.Indicator className="block size-2.5 rounded-full bg-(--choice-checked-bg)" />
    </RadioGroupPrimitive.Item>
  );
}
