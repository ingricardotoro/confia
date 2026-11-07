import { cva, type VariantProps } from "class-variance-authority";
import { CircleAlert, Info, TriangleAlert } from "lucide-react";
import type { ComponentProps, ReactNode } from "react";
import { useUiText } from "../../i18n/use-ui-text";
import type { UiKey } from "../../i18n/resources";
import { cn } from "../../lib/cn";
import { focusRing } from "../../lib/focus-ring";

// docs/ui-ux/02 section 4 and docs/ui-ux/01 section 6: an inline, contextual message. The colors come
// from the Level 3 tokens (--alert-*), which point to the status tokens that have a verified contrast
// in both themes. Every class is written out in full so Tailwind can see it.
const alertVariants = cva("flex items-start gap-3 rounded-md border p-4 text-sm", {
  variants: {
    variant: {
      info: "border-(--alert-info-border) bg-(--alert-info-bg) text-(--alert-info-fg)",
      warning: "border-(--alert-warning-border) bg-(--alert-warning-bg) text-(--alert-warning-fg)",
      destructive: "border-(--alert-destructive-border) bg-(--alert-destructive-bg) text-(--alert-destructive-fg)",
    },
  },
  defaultVariants: { variant: "info" },
});

type AlertVariant = NonNullable<VariantProps<typeof alertVariants>["variant"]>;

// The three channels of a severity besides color: a drawing and a text. The drawing is decorative.
const SEVERITY: Record<AlertVariant, { icon: typeof Info; textKey: UiKey }> = {
  info: { icon: Info, textKey: "alert.severity.info" },
  warning: { icon: TriangleAlert, textKey: "alert.severity.warning" },
  destructive: { icon: CircleAlert, textKey: "alert.severity.destructive" },
};

export interface AlertProps extends Omit<ComponentProps<"div">, "children" | "title" | "role">, VariantProps<typeof alertVariants> {
  /** Short heading of the message. It arrives already translated, like the content. */
  title?: string;
  /** Required on purpose: an alert with nothing to say is noise (DS85). */
  children: ReactNode;
}

// Only what stops the person is assertive (docs/ui-ux/03 section 4): destructive is an alert and the
// rest are a status. `tabIndex` -1 lets code move the focus here, for instance after a failed submit,
// without adding a stop to the tab order. The ref is a plain prop in React 19.
export function Alert({ variant, title, children, className, ...rest }: AlertProps) {
  const { t } = useUiText();
  const severity = variant ?? "info";
  const { icon: Icon, textKey } = SEVERITY[severity];

  return (
    <div
      tabIndex={-1}
      {...rest}
      role={severity === "destructive" ? "alert" : "status"}
      className={cn(alertVariants({ variant: severity }), focusRing, className)}
    >
      <Icon aria-hidden="true" className="mt-0.5 size-5 shrink-0" />
      <div className="flex min-w-0 flex-col gap-1">
        <span className="sr-only">{t(textKey)}</span>
        {title?.trim() ? <p className="font-semibold">{title}</p> : null}
        <div>{children}</div>
      </div>
    </div>
  );
}
