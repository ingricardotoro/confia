import { cva, type VariantProps } from "class-variance-authority";
import type { ComponentProps, ReactNode } from "react";
import { cn } from "../../lib/cn";

// A generic label with four looks. It always renders text, so color is never its only carrier;
// the optional icon is decorative. A financial state is not a Badge variant: StatusBadge (6.2) is
// built on this atom and supplies the --status-badge-* tokens through `className`.
const badge = cva("inline-flex shrink-0 items-center gap-1 rounded-sm border font-medium whitespace-nowrap", {
  variants: {
    variant: {
      neutral: "border-(--badge-neutral-border) bg-(--badge-neutral-bg) text-(--badge-neutral-fg)",
      primary: "border-(--badge-primary-border) bg-(--badge-primary-bg) text-(--badge-primary-fg)",
      success: "border-(--badge-success-border) bg-(--badge-success-bg) text-(--badge-success-fg)",
      outline: "border-(--badge-outline-border) bg-transparent text-(--badge-outline-fg)",
    },
    size: {
      sm: "h-5 px-1.5 text-xs",
      md: "h-6 px-2 text-sm",
    },
  },
  defaultVariants: { variant: "neutral", size: "md" },
});

export interface BadgeProps extends ComponentProps<"span">, VariantProps<typeof badge> {
  /** Decorative icon shown before the text. */
  icon?: ReactNode;
  /**
   * A 6 px decorative dot before the text: filled or hollow, a second non-color carrier of the state.
   * The text still carries the meaning (docs/ui-ux/07 section 3.5).
   */
  dot?: "filled" | "hollow";
}

export function Badge({ variant, size, icon, dot, className, children, ...rest }: BadgeProps) {
  return (
    <span {...rest} className={cn(badge({ variant, size }), className)}>
      {dot === undefined ? null : (
        <span
          aria-hidden="true"
          data-dot={dot}
          className={cn("size-1.5 shrink-0 rounded-full border border-current", dot === "filled" && "bg-current")}
        />
      )}
      {icon === undefined ? null : (
        <span aria-hidden="true" className="inline-flex shrink-0 [&_svg]:size-3.5">
          {icon}
        </span>
      )}
      {children}
    </span>
  );
}
