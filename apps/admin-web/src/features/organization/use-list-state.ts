import type { TableSort } from "@confia/ui";
import type { UseQueryResult } from "@tanstack/react-query";
import type { AdminKey } from "../../i18n/resources.ts";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import type { ListTableState, ResourcePagination } from "./components/ResourceScreen.tsx";
import { searchSortOf, tableSortOf } from "./list-search.ts";
import { loadFailureOf } from "./organization-failure.ts";
import type { SearchChange } from "./search-change.ts";

interface Page<Row> {
  readonly items: readonly Row[];
  readonly total: number;
}

interface ListSearch {
  readonly page: number;
  readonly pageSize: number;
  readonly sort?: string | undefined;
}

export interface ListStateOptions<Row, S extends ListSearch> {
  readonly list: UseQueryResult<Page<Row>>;
  readonly search: S;
  /** Every change of the search goes through here, so the URL is the one place the state of the table lives. */
  readonly onSearchChange: SearchChange<S>;
  /** Whether a filter is applied. */
  readonly filtered: boolean;
  /** The search with no filters and on the first page. */
  readonly cleared: S;
  readonly onCreate: () => void;
  readonly loadFailedKey: AdminKey;
  readonly emptyKeys: { readonly title: AdminKey; readonly description: AdminKey; readonly action: AdminKey };
  /** The order the server applies when the URL names none, so the header can show it. */
  readonly defaultSort?: TableSort;
}

/**
 * The state of a listing as the table and the pages need it: the four states of the load (loading, failed, empty,
 * with rows), the order the server applied, and the changes of the search. The empty state has two faces: nothing
 * exists yet (the way forward is to create), or the filters leave nothing (the way forward is to clear them).
 */
export function useListState<Row, S extends ListSearch>({
  list,
  search,
  onSearchChange,
  filtered,
  cleared,
  onCreate,
  loadFailedKey,
  emptyKeys,
  defaultSort,
}: ListStateOptions<Row, S>): { rows: readonly Row[]; table: ListTableState; pagination: ResourcePagination } {
  const { t } = useAdminText();
  const rows = list.data?.items ?? [];
  const total = list.data?.total ?? 0;
  const failure = list.isError ? loadFailureOf(list.error) : undefined;
  // A page past the end of the list is empty too, but the list is not: the way forward is the first page.
  const noMatches = filtered || total > 0;
  const sort = tableSortOf(search.sort) ?? defaultSort;

  return {
    rows,
    table: {
      loading: list.isPending,
      ...(failure === undefined
        ? {}
        : {
            error: {
              title: t(loadFailedKey),
              description: [t(failure.key), failure.traceId === undefined ? "" : t("organization.common.trace", { traceId: failure.traceId })]
                .filter((part) => part !== "")
                .join(" "),
              onRetry: () => void list.refetch(),
            },
          }),
      empty: noMatches
        ? {
            title: t("organization.common.noResultsTitle"),
            description: t("organization.common.noResultsDescription"),
            action: {
              label: t("organization.common.clearFilters"),
              onAction: () => {
                onSearchChange(cleared, { replace: true });
              },
            },
          }
        : { title: t(emptyKeys.title), description: t(emptyKeys.description), action: { label: t(emptyKeys.action), onAction: onCreate } },
      sort,
      onSortChange: (next) => {
        onSearchChange({ ...search, sort: searchSortOf(next), page: 1 });
      },
    },
    pagination: {
      page: search.page - 1,
      size: search.pageSize,
      total,
      busy: list.isFetching,
      onPageChange: (page) => {
        onSearchChange({ ...search, page: page + 1 });
      },
    },
  };
}
