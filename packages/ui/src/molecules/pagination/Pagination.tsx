import { ChevronLeft, ChevronRight } from "lucide-react";
import type { ComponentProps } from "react";
import { Button } from "../../atoms/button";
import { useUiText } from "../../i18n/use-ui-text";
import { cn } from "../../lib/cn";

export interface PaginationProps extends Omit<ComponentProps<"nav">, "children" | "aria-label"> {
  /** Zero-based, as the API reports it. The person sees it as page + 1. */
  page: number;
  /** Rows per page the server applied. */
  size: number;
  /** Total rows on the server, not the loaded ones. */
  total: number;
  onPageChange: (page: number) => void;
  /** Blocks both buttons while a page is being fetched. */
  disabled?: boolean;
}

// Server-side pagination (docs/ui-ux/02 section 5.3): the component never slices data, it only asks
// for another page. With no rows there is nothing to page, so it renders no landmark at all. The
// summary is a polite live region so a screen reader hears that the page changed.
export function Pagination({ page, size, total, onPageChange, disabled = false, className, ...rest }: PaginationProps) {
  const { t } = useUiText();
  if (total <= 0 || size <= 0) return null;

  const pages = Math.ceil(total / size);
  const current = Math.min(Math.max(page, 0), pages - 1);
  const from = current * size + 1;
  const to = Math.min(total, (current + 1) * size);

  return (
    <nav {...rest} aria-label={t("pagination.label")} className={cn("flex flex-wrap items-center justify-between gap-3 text-sm", className)}>
      <p aria-live="polite" className="text-text-secondary">
        {t("pagination.summary", { from, to, total })}
      </p>
      <div className="flex items-center gap-2">
        <Button variant="secondary" size="sm" disabled={disabled || current === 0} onClick={() => {
            onPageChange(current - 1);
          }}>
          <ChevronLeft aria-hidden="true" />
          {t("pagination.previous")}
        </Button>
        <span className="text-text-secondary">{t("pagination.page", { page: current + 1, pages })}</span>
        <Button variant="secondary" size="sm" disabled={disabled || current >= pages - 1} onClick={() => {
            onPageChange(current + 1);
          }}>
          {t("pagination.next")}
          <ChevronRight aria-hidden="true" />
        </Button>
      </div>
    </nav>
  );
}
