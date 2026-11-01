import type { Money } from "@confia/contracts/portal";
import { cva } from "class-variance-authority";
import type { ComponentProps } from "react";
import { formatMoney } from "../../format/money";
import { DEFAULT_UI_LANGUAGE } from "../../i18n/resources";
import { useUiText } from "../../i18n/use-ui-text";
import { cn } from "../../lib/cn";

// The `money-amount` class (tokens/base.css) forces tabular numerals and cannot be switched off by
// the caller. Alignment to the right belongs to the table cell that holds the amount, not to this
// component (design.md decision 12). Every figure comes from `formatMoney`: nothing here formats,
// rounds or converts an amount (CLAUDE.md rules 1 and 3).
const moneyAmount = cva("money-amount whitespace-nowrap", {
  variants: {
    size: { sm: "text-sm", md: "text-base", lg: "text-lg", xl: "text-2xl" },
    emphasis: { normal: "", strong: "font-semibold text-text-primary", muted: "text-text-secondary" },
  },
  defaultVariants: { size: "md", emphasis: "normal" },
});

const SIGN_COLOR = {
  negative: "text-status-overdue-fg",
  zero: "",
  positive: "text-status-paid-fg",
} as const;

export interface MoneyAmountProps extends Omit<ComponentProps<"span">, "children"> {
  /** The API money DTO, already rounded by the server to the currency's minor unit. */
  value: Money;
  emphasis?: "normal" | "strong" | "muted";
  size?: "sm" | "md" | "lg" | "xl";
  /** Tints positives and negatives with a semantic token. Only for variations; the sign stays in the text. */
  colorBySign?: boolean;
  /** Shows a plus sign before positive amounts (never before zero). Only for variations. */
  showPositiveSign?: boolean;
  /** Wraps negatives in parentheses. Wins over `showPositiveSign` when both are set. */
  accounting?: boolean;
  /** Omits the currency symbol, for tables whose header already names the currency. */
  hideCurrency?: boolean;
}

export function MoneyAmount({
  value,
  emphasis,
  size,
  colorBySign = false,
  showPositiveSign = false,
  accounting = false,
  hideCurrency = false,
  className,
  ...rest
}: MoneyAmountProps) {
  const { t } = useUiText();
  const formatted = formatMoney(value, DEFAULT_UI_LANGUAGE, {
    sign: accounting ? "accounting" : showPositiveSign ? "always" : "auto",
    hideCurrency,
  });

  // An amount outside the contract shows the catalog text, never a guessed or rounded figure.
  if (formatted.kind === "invalid") {
    return (
      <span {...rest} className={cn(moneyAmount({ size, emphasis }), className)}>
        {t("money.unavailable")}
      </span>
    );
  }

  // The visual form is hidden from assistive technology and the spoken form carries the amount with
  // the full currency name from Intl, so a screen reader never announces a bare number (docs/ui-ux/03).
  return (
    <span
      {...rest}
      className={cn(moneyAmount({ size, emphasis }), colorBySign && SIGN_COLOR[formatted.sign], className)}
    >
      <span aria-hidden="true">{formatted.visual}</span>
      <span className="sr-only">{formatted.spoken}</span>
    </span>
  );
}
