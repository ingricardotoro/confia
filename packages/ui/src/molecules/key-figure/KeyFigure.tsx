import type { ReactNode } from "react";
import { Skeleton } from "../../atoms/skeleton";
import { cn } from "../../lib/cn";

export interface KeyFigureProps {
  /** Already translated. Shown in small caps above the figure. */
  label: string;
  /** A MoneyAmount, or a count already formatted by Intl. The server's figure: nothing here operates on it. */
  value: ReactNode;
  description?: string;
  /** Shows a skeleton with the shape of the figure. */
  loading?: boolean;
  className?: string;
}

// One headline figure of a page (docs/ui-ux/07 section 6.2). It displays what the server sent and
// never computes: it takes no number to add, round or compare.
export function KeyFigure({ label, value, description, loading = false, className }: KeyFigureProps) {
  return (
    <div
      className={cn("flex flex-col gap-1 rounded-lg border border-border-subtle bg-bg-subtle px-4 py-3", className)}
      aria-busy={loading || undefined}
    >
      <p className="text-xs font-semibold tracking-caps text-text-secondary uppercase">{label}</p>
      {loading ? (
        <Skeleton className="h-8 w-24" />
      ) : (
        <p className="text-2xl font-semibold tracking-tight text-text-primary tabular-nums">{value}</p>
      )}
      {description === undefined ? null : <p className="text-sm text-text-secondary">{description}</p>}
    </div>
  );
}
