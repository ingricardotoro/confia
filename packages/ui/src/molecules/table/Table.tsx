import { ArrowDown, ArrowUp, ChevronsUpDown } from "lucide-react";
import type { ComponentProps, ReactNode } from "react";
import { Skeleton } from "../../atoms/skeleton";
import { useUiText } from "../../i18n/use-ui-text";
import { cn } from "../../lib/cn";
import { focusRing } from "../../lib/focus-ring";
import { EmptyState, type EmptyStateProps } from "../empty-state";
import { ErrorState, type ErrorStateProps } from "../error-state";

export type SortDirection = "asc" | "desc";

export interface TableColumn<Row> {
  /** Stable identifier. It is what `onSortChange` reports, so it matches the server's sort field. */
  id: string;
  /** The visible header text, already translated. */
  header: string;
  cell: (row: Row) => ReactNode;
  /** Renders the header as a button that asks the server for this order. */
  sortable?: boolean;
  /** Numbers and amounts align to the end (docs/ui-ux/02). */
  align?: "start" | "end";
}

export interface TableSort {
  columnId: string;
  direction: SortDirection;
}

export interface TableProps<Row> extends Omit<ComponentProps<"div">, "children"> {
  /** Names the table for assistive technology and is shown above it. Required: a table has a name. */
  caption: string;
  columns: readonly TableColumn<Row>[];
  rows: readonly Row[];
  getRowId: (row: Row) => string;
  /** First load: skeleton rows with the shape of the real ones. Takes precedence over every other state. */
  loading?: boolean;
  /** Number of skeleton rows while loading. */
  skeletonRows?: number;
  /** A failed load. Replaces the body with an ErrorState that offers a retry. */
  error?: Pick<ErrorStateProps, "title" | "description" | "onRetry" | "retryLabel">;
  /** Shown when the load succeeded and there are no rows. Required: an empty list needs a way forward. */
  empty: Pick<EmptyStateProps, "title" | "description" | "action">;
  /** The order the server applied. Sorting itself is the server's job: the table only reports clicks. */
  sort?: TableSort | undefined;
  onSortChange?: (sort: TableSort) => void;
}

const ARIA_SORT = { asc: "ascending", desc: "descending" } as const;

// Presentational only (docs/ui-ux/02 section 5.3): rows arrive already paged, ordered and filtered by
// the server, and a click on a sortable header reports the order it wants. Semantic markup: caption,
// scope="col" headers, aria-sort on the ordered column only. Loading, error and empty each replace
// the body explicitly and never collapse into one another.
export function Table<Row>({
  caption,
  columns,
  rows,
  getRowId,
  loading = false,
  skeletonRows = 5,
  error,
  empty,
  sort,
  onSortChange,
  className,
  ...rest
}: TableProps<Row>) {
  const { t } = useUiText();

  function nextSort(columnId: string): TableSort {
    return { columnId, direction: sort?.columnId === columnId && sort.direction === "asc" ? "desc" : "asc" };
  }

  function body(): ReactNode {
    const span = columns.length;
    if (loading) {
      return Array.from({ length: skeletonRows }, (_, index) => (
        <tr key={index} className="border-t border-border-subtle">
          {columns.map((column) => (
            <td key={column.id} className="px-3 py-3">
              <Skeleton className="h-4 w-full max-w-40" />
            </td>
          ))}
        </tr>
      ));
    }
    if (error !== undefined) {
      return (
        <tr className="border-t border-border-subtle">
          <td colSpan={span}>
            <ErrorState {...error} />
          </td>
        </tr>
      );
    }
    if (rows.length === 0) {
      return (
        <tr className="border-t border-border-subtle">
          <td colSpan={span}>
            <EmptyState {...empty} />
          </td>
        </tr>
      );
    }
    return rows.map((row) => (
      <tr key={getRowId(row)} className="border-t border-border-subtle">
        {columns.map((column) => (
          <td key={column.id} className={cn("px-3 py-2 text-text-primary", column.align === "end" && "text-right tabular-nums")}>
            {column.cell(row)}
          </td>
        ))}
      </tr>
    ));
  }

  return (
    <div {...rest} className={cn("w-full overflow-x-auto rounded-md border border-border-subtle bg-bg-surface", className)}>
      {loading ? (
        <p role="status" className="sr-only">
          {t("common.loading")}
        </p>
      ) : null}
      <table aria-busy={loading || undefined} className="w-full border-collapse text-left text-sm">
        <caption className="px-3 py-2 text-left text-sm font-semibold text-text-primary">{caption}</caption>
        <thead className="bg-bg-canvas">
          <tr>
            {columns.map((column) => {
              const active = sort?.columnId === column.id ? sort.direction : undefined;
              const alignEnd = column.align === "end";
              return (
                <th
                  key={column.id}
                  scope="col"
                  aria-sort={column.sortable === true && active !== undefined ? ARIA_SORT[active] : undefined}
                  className={cn("px-3 py-2 font-semibold text-text-secondary", alignEnd && "text-right")}
                >
                  {column.sortable === true ? (
                    <button
                      type="button"
                      onClick={() => onSortChange?.(nextSort(column.id))}
                      className={cn("inline-flex min-h-8 items-center gap-1 rounded-sm font-semibold", alignEnd && "flex-row-reverse", focusRing)}
                    >
                      {column.header}
                      {active === "asc" ? (
                        <ArrowUp className="size-4" />
                      ) : active === "desc" ? (
                        <ArrowDown className="size-4" />
                      ) : (
                        <ChevronsUpDown className="size-4 text-text-tertiary" />
                      )}
                    </button>
                  ) : (
                    column.header
                  )}
                </th>
              );
            })}
          </tr>
        </thead>
        <tbody>{body()}</tbody>
      </table>
    </div>
  );
}
