import type { ComponentProps } from "react";
import { Badge } from "../../atoms/badge";
import { useUiText } from "../../i18n/use-ui-text";
import { cn } from "../../lib/cn";
import { STATUS_TABLE, UNKNOWN_STATUS, type FinancialStatus } from "./status-table";

// The look of each variant, over the --sb-* variables that the tone of the state binds to its tokens.
const VARIANT_LOOK = {
  soft: "border-(--sb-border) bg-(--sb-bg) text-(--sb-fg)",
  solid: "border-(--sb-solid) bg-(--sb-solid) text-(--status-badge-solid-fg)",
  outline: "border-(--sb-border) bg-transparent text-(--sb-fg)",
} as const;

export interface StatusBadgeProps extends Omit<ComponentProps<"span">, "children" | "title"> {
  status: FinancialStatus;
  /** `soft` by default. `solid` only for the dominant state of a page. */
  variant?: keyof typeof VARIANT_LOOK;
  size?: "sm" | "md";
  /** For very dense tables. The text stays for assistive technology and as the title. */
  iconOnly?: boolean;
}

/**
 * A financial state always travels as color, icon and text at the same time (docs/ui-ux/01 section
 * 4). The icon is decorative: the text is the one announcement, in the sr-only span when `iconOnly`.
 */
export function StatusBadge({ status, variant = "soft", size, iconOnly = false, className, ...rest }: StatusBadgeProps) {
  const { t } = useUiText();
  // `status` is typed, but a server that adds a state before the front end does reaches this at run
  // time. It shows the neutral text, never a guessed state and never an inherited property.
  const known = Object.hasOwn(STATUS_TABLE, status);
  const { icon: Icon, textKey, tone } = known ? STATUS_TABLE[status] : UNKNOWN_STATUS;
  const text = t(textKey);

  return (
    <Badge
      {...rest}
      {...(iconOnly ? { title: text } : {})}
      variant={variant === "outline" ? "outline" : "neutral"}
      size={size}
      icon={<Icon />}
      className={cn(tone, known && VARIANT_LOOK[variant], iconOnly && "px-1.5", className)}
    >
      {iconOnly ? <span className="sr-only">{text}</span> : text}
    </Badge>
  );
}
