import { ChevronRight } from "lucide-react";
import { cn } from "../../lib/cn";
import { focusRing } from "../../lib/focus-ring";

export interface BreadcrumbItem {
  id: string;
  /** Already translated. */
  label: string;
  /** Absent on the current page (always the last one) and on a level that has no page of its own. */
  href?: string;
  /** Called on a plain click so the app can navigate without a reload. A modified click keeps its browser meaning. */
  onNavigate?: (href: string) => void;
}

export interface BreadcrumbProps {
  /** Names the landmark for assistive technology, already translated. */
  label: string;
  items: readonly BreadcrumbItem[];
  className?: string;
}

// Where the person is, and the way back (docs/ui-ux/07 section 6.2). The last item is the current
// page: plain text with aria-current="page", never a link. Separators are decorative.
export function Breadcrumb({ label, items, className }: BreadcrumbProps) {
  return (
    <nav aria-label={label} className={className}>
      <ol className="flex flex-wrap items-center gap-1 text-xs font-medium text-text-secondary">
        {items.map((item, index) => {
          const last = index === items.length - 1;
          const href = item.href;
          return (
            <li key={item.id} className="flex items-center gap-1">
              {last || href === undefined ? (
                <span aria-current={last ? "page" : undefined} className={cn(last && "text-text-primary")}>
                  {item.label}
                </span>
              ) : (
                <a
                  href={href}
                  onClick={(event) => {
                    if (item.onNavigate === undefined) return;
                    if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
                    event.preventDefault();
                    item.onNavigate(href);
                  }}
                  className={cn("rounded-sm underline-offset-2 hover:text-text-primary hover:underline", focusRing)}
                >
                  {item.label}
                </a>
              )}
              {last ? null : <ChevronRight aria-hidden="true" className="size-3.5 shrink-0 text-text-tertiary" />}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
