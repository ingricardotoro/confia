import { Alert, Button, PageHeader, Pagination, Table, type TableProps } from "@confia/ui";
import type { ReactNode } from "react";
import { useAdminText } from "../../../i18n/use-admin-text.ts";

/** The result of the last action of the person, already translated. */
export interface ResourceNotice {
  readonly tone: "success" | "error";
  readonly message: string;
  readonly traceId?: string;
}

/** What the table shows besides its rows and columns: the state of the load, and the order the server applied. */
export type ListTableState = Pick<TableProps<unknown>, "loading" | "error" | "empty" | "sort" | "onSortChange">;

/** `page` counts from 0, as the API does. */
export interface ResourcePagination {
  readonly page: number;
  readonly size: number;
  readonly total: number;
  readonly onPageChange: (page: number) => void;
  /** A page is being fetched: both buttons wait. */
  readonly busy: boolean;
}

export interface ResourceScreenProps<Row> {
  /** The level 2 heading of the view. The level 1 is the area's. */
  readonly heading: string;
  /** Already translated. A line under the heading that says what the view is for. */
  readonly description?: string | undefined;
  readonly createLabel: string;
  readonly onCreate: () => void;
  /** The filter controls of the view, if it has any. */
  readonly filters?: ReactNode;
  readonly notice?: ResourceNotice | undefined;
  /** Set when the lookup lists (the options of the filters and of the form) failed to load. */
  readonly lookupFailure?: { readonly onRetry: () => void } | undefined;
  readonly table: ListTableState & Pick<TableProps<Row>, "caption" | "columns" | "rows" | "getRowId">;
  readonly pagination: ResourcePagination;
}

/**
 * What the four views share: the heading with the one primary action, the filters, the region that tells the result
 * of the last action, and the table with its pages. The rows arrive already paged, ordered and filtered by the
 * server. The region is always in the document, so a message put in it is announced.
 */
export function ResourceScreen<Row>({ heading, description, createLabel, onCreate, filters, notice, lookupFailure, table, pagination }: ResourceScreenProps<Row>) {
  const { t } = useAdminText();

  return (
    <section aria-labelledby="organization-view-heading" className="flex flex-col gap-3">
      <PageHeader
        title={heading}
        headingLevel={2}
        headingId="organization-view-heading"
        {...(description === undefined ? {} : { description })}
        actions={<Button size="sm" onClick={onCreate}>{createLabel}</Button>}
      />
      {filters === undefined ? null : (
        <div role="group" aria-label={t("resource.filters")} className="flex flex-wrap items-end gap-x-3 gap-y-2 rounded-lg border border-border-subtle bg-bg-surface px-3 py-2">
          {filters}
        </div>
      )}
      {lookupFailure === undefined ? null : (
        <Alert variant="warning">
          <p>{t("resource.optionsFailed")}</p>
          <Button variant="secondary" size="xs" className="mt-2" onClick={lookupFailure.onRetry}>
            {t("resource.retryOptions")}
          </Button>
        </Alert>
      )}
      <div aria-live="polite" aria-atomic="true" className="empty:-mt-3">
        {notice === undefined ? null : (
          <Alert variant={notice.tone === "error" ? "destructive" : "info"}>
            <p>{notice.message}</p>
            {notice.traceId === undefined ? null : <p className="mt-1">{t("resource.trace", { traceId: notice.traceId })}</p>}
          </Alert>
        )}
      </div>
      <Table stickyHeader captionHidden {...table} />
      <Pagination
        page={pagination.page}
        size={pagination.size}
        total={pagination.total}
        onPageChange={pagination.onPageChange}
        disabled={pagination.busy}
      />
    </section>
  );
}
