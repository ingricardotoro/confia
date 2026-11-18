import { useId, type ReactNode } from "react";
import { cn } from "../../lib/cn";

export interface CardProps {
  /** Already translated. It names the card as a region when given. */
  title?: string;
  description?: string;
  /** Actions in the header, on the right. */
  actions?: ReactNode;
  padding?: "sm" | "md" | "lg";
  children: ReactNode;
  className?: string;
}

// A bordered surface for a block of a detail screen, reading the --card-* tokens (docs/ui-ux/07 section 5.3).
export function Card({ title, description, actions, padding = "md", children, className }: CardProps) {
  const titleId = useId();
  const pad = padding === "lg" ? "p-6" : padding === "sm" ? "p-3" : "p-4";
  return (
    <section
      aria-labelledby={title === undefined ? undefined : titleId}
      className={cn("rounded-lg border border-(--card-border) bg-(--card-bg) shadow-(--card-shadow)", className)}
    >
      {title === undefined && actions === undefined ? null : (
        <header className={cn("flex flex-wrap items-start justify-between gap-3 border-b border-(--card-border)", pad)}>
          <div className="flex flex-col gap-0.5">
            {title === undefined ? null : (
              <h3 id={titleId} className="text-base font-semibold text-text-primary">
                {title}
              </h3>
            )}
            {description === undefined ? null : <p className="text-sm text-text-secondary">{description}</p>}
          </div>
          {actions}
        </header>
      )}
      <div className={pad}>{children}</div>
    </section>
  );
}
