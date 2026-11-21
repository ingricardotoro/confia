import { useNavigate } from "@tanstack/react-router";
import { nameMapOf, optionsOf, useGradeLookup, useModalityLookup, useYearLookup } from "../../shared/organization-lookups/use-lookups.ts";
import { toApiParams } from "../../shared/resource-screen/list-search.ts";
import { usePermissions } from "../../shared/permissions/permissions.tsx";
import type { SearchChange } from "../../shared/resource-screen/search-change.ts";
import { useListState } from "../../shared/resource-screen/use-list-state.ts";
import { useResourceView } from "../../shared/resource-screen/use-resource-view.ts";
import { PRICE_LIST_SCOPES, PRICE_LIST_STATUSES, type PriceListsSearch } from "./catalog-search.ts";
import { priceListPath } from "./catalog-paths.ts";
import { PriceListsView } from "./components/PriceListsView.tsx";
import { PriceListFormContainer } from "./PriceListFormContainer.tsx";
import type { PriceList } from "./price-list-form.ts";
import { usePriceListCreate, usePriceListList } from "./use-catalog-queries.ts";

export interface PriceListsContainerProps {
  readonly search: PriceListsSearch;
  readonly onSearchChange: SearchChange<PriceListsSearch>;
}

/**
 * The price lists: the list as the server serves it (filtered, ordered and paged there), the form of a new list in a
 * dialog, and a link from each row to its detail. There is no edit and no delete here: a list is archived from its
 * detail, and its year, scope and reference never change.
 */
export function PriceListsContainer({ search, onSearchChange }: PriceListsContainerProps) {
  const { can } = usePermissions();
  const navigate = useNavigate();
  const list = usePriceListList(toApiParams(search));
  const create = usePriceListCreate();
  const view = useResourceView<PriceList>();
  const years = useYearLookup();
  const modalities = useModalityLookup();
  const grades = useGradeLookup();
  const lookupsFailed = years.failed || modalities.failed || grades.failed;
  const retryLookups = () => {
    if (years.failed) years.retry();
    if (modalities.failed) modalities.retry();
    if (grades.failed) grades.retry();
  };
  const lookupFailure = lookupsFailed ? { onRetry: retryLookups } : undefined;
  const state = useListState({
    list,
    search,
    onSearchChange,
    filtered: search.academicYearId !== undefined || search.scope !== undefined || search.status !== undefined,
    cleared: { page: 1, pageSize: search.pageSize, sort: search.sort, academicYearId: undefined, scope: undefined, status: undefined },
    onCreate: can("catalog:create") ? view.openCreate : undefined,
    loadFailedKey: "catalog.priceLists.loadFailed",
    emptyKeys: {
      title: "catalog.priceLists.emptyTitle",
      description: "catalog.priceLists.emptyDescription",
      action: "catalog.priceLists.emptyAction",
    },
    defaultSort: { columnId: "name", direction: "asc" },
  });
  const referenceNames = new Map([...nameMapOf(modalities.rows), ...nameMapOf(grades.rows)]);

  return (
    <>
      <PriceListsView
        rows={state.rows}
        table={state.table}
        pagination={state.pagination}
        notice={view.notice}
        yearNames={nameMapOf(years.rows)}
        referenceNames={referenceNames}
        yearOptions={optionsOf(years.rows)}
        lookupFailure={lookupFailure}
        filters={{
          year: {
            value: search.academicYearId ?? "",
            onChange: (year) => {
              onSearchChange({ ...search, academicYearId: years.rows.find((known) => known.id === year)?.id, page: 1 }, { replace: true });
            },
          },
          scope: {
            value: search.scope ?? "",
            onChange: (scope) => {
              onSearchChange({ ...search, scope: PRICE_LIST_SCOPES.find((known) => known === scope), page: 1 }, { replace: true });
            },
          },
          status: {
            value: search.status ?? "",
            onChange: (status) => {
              onSearchChange({ ...search, status: PRICE_LIST_STATUSES.find((known) => known === status), page: 1 }, { replace: true });
            },
          },
        }}
        hrefOf={(row) => priceListPath(row.id)}
        onOpen={(row) => void navigate({ to: priceListPath(row.id) })}
        onCreate={can("catalog:create") ? view.openCreate : undefined}
      />
      {view.dialog.kind === "create" ? (
        <PriceListFormContainer
          yearOptions={optionsOf(years.rows)}
          modalityOptions={optionsOf(modalities.rows)}
          gradeOptions={optionsOf(grades.rows)}
          lookupFailure={lookupFailure}
          save={async (body) => {
            const saved = await create.mutateAsync(body);
            view.saved("created", saved.name);
          }}
          onClose={view.close}
        />
      ) : null}
    </>
  );
}
