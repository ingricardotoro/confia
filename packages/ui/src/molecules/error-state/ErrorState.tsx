import { TriangleAlert } from "lucide-react";
import type { ComponentProps } from "react";
import { Button } from "../../atoms/button";
import { useUiText } from "../../i18n/use-ui-text";
import { cn } from "../../lib/cn";

export interface ErrorStateProps extends Omit<ComponentProps<"div">, "children" | "title"> {
  /** What failed and what the person can do, without technical jargon. */
  title: string;
  description?: string;
  /** Required on purpose (ADR-0018, docs/ui-ux/02): an error with no retry is a dead end. */
  onRetry: () => void;
  /** Defaults to the common.retry entry of the catalog. */
  retryLabel?: string;
}

// The message sits in a live region (role alert), so a failure that appears after the content has
// loaded is announced without moving the focus. The retry stays outside it: announcing a button
// would only repeat its name. The drawing is decorative and the message carries the meaning.
export function ErrorState({ title, description, onRetry, retryLabel, className, ...rest }: ErrorStateProps) {
  const { t } = useUiText();

  return (
    <div {...rest} className={cn("flex flex-col items-center gap-3 px-6 py-10 text-center", className)}>
      <div role="alert" className="flex max-w-prose flex-col items-center gap-3">
        <TriangleAlert className="size-8 text-status-overdue-fg" />
        <div className="flex flex-col gap-1">
          <p className="text-base font-semibold text-text-primary">{title}</p>
          {description === undefined ? null : <p className="text-sm text-text-secondary">{description}</p>}
        </div>
      </div>
      <Button variant="secondary" onClick={onRetry}>
        {retryLabel ?? t("common.retry")}
      </Button>
    </div>
  );
}
