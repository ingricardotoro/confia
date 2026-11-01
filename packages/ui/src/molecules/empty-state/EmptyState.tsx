import { Inbox } from "lucide-react";
import type { ComponentProps } from "react";
import { Button } from "../../atoms/button";
import { cn } from "../../lib/cn";

export interface EmptyStateProps extends Omit<ComponentProps<"div">, "children" | "title"> {
  /** What is missing, specific to the context ("Sin cargos en el período"), never a bare "no results". */
  title: string;
  description?: string;
  /**
   * What resolves the emptiness. Required on purpose (ADR-0018, docs/ui-ux/02): an empty state with
   * no way forward is a dead end. Text arrives already translated, like every business text.
   */
  action: { label: string; onAction: () => void };
}

// An empty list is not a failure, so it has no live role: it is content, read where it sits. The
// title is a paragraph and not a heading because the level depends on the page that holds it.
export function EmptyState({ title, description, action, className, ...rest }: EmptyStateProps) {
  return (
    <div {...rest} className={cn("flex flex-col items-center gap-3 px-6 py-10 text-center", className)}>
      <Inbox className="size-8 text-text-tertiary" />
      <div className="flex max-w-prose flex-col gap-1">
        <p className="text-base font-semibold text-text-primary">{title}</p>
        {description === undefined ? null : <p className="text-sm text-text-secondary">{description}</p>}
      </div>
      <Button variant="secondary" onClick={action.onAction}>
        {action.label}
      </Button>
    </div>
  );
}
