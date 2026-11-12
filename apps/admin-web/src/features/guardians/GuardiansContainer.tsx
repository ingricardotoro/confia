import type { TableSort } from "@confia/ui";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { toApiParams } from "../organization/list-search.ts";
import type { SearchChange } from "../organization/search-change.ts";
import { useListState } from "../organization/use-list-state.ts";
import { useResourceView } from "../organization/use-resource-view.ts";
import { useSearchBox } from "../students/use-search-box.ts";
import { GuardiansView } from "./components/GuardiansView.tsx";
import { GuardianDetailContainer } from "./GuardianDetailContainer.tsx";
import { GuardianFormContainer } from "./GuardianFormContainer.tsx";
import { nameOf, toggleBodyOf, type Guardian } from "./guardian-form.ts";
import type { GuardiansSearch } from "./guardians-search.ts";
import { useGuardianList, useGuardianWrite } from "./use-guardians-queries.ts";
import { useState } from "react";

export interface GuardiansContainerProps {
  readonly search: GuardiansSearch;
  readonly onSearchChange: SearchChange<GuardiansSearch>;
}

/** The order the server applies when the address names none: by last name, ascending. */
const DEFAULT_SORT: TableSort = { columnId: "lastName", direction: "asc" };

/**
 * The guardians: the list as the server serves it (searched, filtered, ordered and paged there), the form and the detail in
 * dialogs, and activate or deactivate from the row. The state of the table is the address's: the search box only follows
 * it. A guardian that is deactivated while it still has students stays linked to them: the notice says so, because the
 * server keeps the links and the staff decides what to do with them.
 */
export function GuardiansContainer({ search, onSearchChange }: GuardiansContainerProps) {
  const { t } = useAdminText();
  const list = useGuardianList(toApiParams(search));
  const write = useGuardianWrite();
  const view = useResourceView<Guardian>();
  const [detailOf, setDetailOf] = useState<Guardian>();
  const box = useSearchBox(search.q, (q) => {
    onSearchChange({ ...search, q, page: 1 }, { replace: true });
  });
  const state = useListState({
    list,
    search,
    onSearchChange,
    filtered: search.q !== undefined || search.status !== undefined,
    cleared: { page: 1, pageSize: search.pageSize, sort: search.sort, q: undefined, status: undefined },
    onCreate: view.openCreate,
    loadFailedKey: "guardians.loadFailed",
    emptyKeys: { title: "guardians.emptyTitle", description: "guardians.emptyDescription", action: "guardians.emptyAction" },
    defaultSort: DEFAULT_SORT,
  });

  /** An inactive guardian that still has students keeps its links: the person is told. */
  const warningOf = (saved: Guardian): string | undefined => {
    const count = saved.students?.length ?? 0;
    return saved.status === "INACTIVE" && count > 0 ? t("guardians.deactivatedWithLinks", { count }) : undefined;
  };

  return (
    <>
      <GuardiansView
        rows={state.rows}
        table={state.table}
        pagination={state.pagination}
        notice={view.notice}
        togglingId={view.togglingId}
        filters={{
          search: { value: box.draft, onChange: box.onDraftChange, tooShort: box.tooShort },
          status: {
            value: search.status ?? "",
            onChange: (status) => {
              onSearchChange({ ...search, status: status === "" ? undefined : status, page: 1 }, { replace: true });
            },
          },
        }}
        onCreate={view.openCreate}
        onEdit={view.openEdit}
        onDetail={(row) => {
          setDetailOf(row);
        }}
        onToggle={(row) => {
          const next = row.status === "ACTIVE" ? "INACTIVE" : "ACTIVE";
          void view.toggle(row.id, nameOf(row), next === "ACTIVE", () => write({ mode: "update", id: row.id, body: toggleBodyOf(row, next) }), warningOf);
        }}
      />
      {view.dialog.kind === "closed" ? null : (
        <GuardianFormContainer
          // A new form for each record, so nothing typed for one is shown for another.
          key={view.dialog.kind === "edit" ? view.dialog.row.id : "create"}
          {...(view.dialog.kind === "edit" ? { row: view.dialog.row } : {})}
          save={async (save) => {
            const saved = await write(save);
            view.saved(save.mode === "create" ? "created" : "updated", nameOf(saved), warningOf(saved));
          }}
          onClose={view.close}
        />
      )}
      {detailOf === undefined ? null : (
        <GuardianDetailContainer
          guardianId={detailOf.id}
          onClose={() => {
            setDetailOf(undefined);
          }}
        />
      )}
    </>
  );
}
