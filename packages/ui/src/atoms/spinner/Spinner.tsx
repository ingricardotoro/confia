import { cva, type VariantProps } from "class-variance-authority";
import { LoaderCircle } from "lucide-react";
import type { ComponentProps } from "react";
import { useUiText } from "../../i18n/use-ui-text";
import { cn } from "../../lib/cn";

// docs/ui-ux/02 section 4: own component, no shadcn base. Only for an action in progress; content
// that is loading uses a Skeleton of the same shape instead.
const spinner = cva("inline-flex shrink-0 text-primary", {
  variants: { size: { sm: "size-4", md: "size-6", lg: "size-8" } },
  defaultVariants: { size: "md" },
});

export interface SpinnerProps
  extends Omit<ComponentProps<"span">, "children" | "role" | "aria-label">,
    VariantProps<typeof spinner> {
  /** Names the action in progress. Defaults to the common.loading catalog entry. */
  label?: string;
}

export function Spinner({ size, label, className, ...rest }: SpinnerProps) {
  const { t } = useUiText();

  return (
    <span {...rest} role="status" aria-label={label ?? t("common.loading")} className={cn(spinner({ size }), className)}>
      <LoaderCircle aria-hidden="true" className="size-full animate-(--spinner-animation)" />
    </span>
  );
}
