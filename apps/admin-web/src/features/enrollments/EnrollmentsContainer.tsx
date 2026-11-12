import type { TableSort } from "@confia/ui";
import { useEffect, useRef, useState } from "react";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { toApiParams } from "../organization/list-search.ts";
import type { ResourceNotice } from "../organization/components/ResourceScreen.tsx";
import type { SearchChange } from "../organization/search-change.ts";
import { useListState } from "../organization/use-list-state.ts";
import { optionsOf, useYearLookup } from "../organization/use-lookups.ts";
import { EnrollmentsView } from "./components/EnrollmentsView.tsx";
import { EnrollFormContainer } from "./EnrollFormContainer.tsx";
import { EnrollmentDetailContainer } from "./EnrollmentDetailContainer.tsx";
import { detailButtonId, studentNameOf, type Enrollment } from "./enrollment-model.ts";
import type { EnrollmentsSearch } from "./enrollments-search.ts";
import { ExitFormContainer } from "./ExitFormContainer.tsx";
import { TransferFormContainer } from "./TransferFormContainer.tsx";
import { useEnrollmentList, useEnrollmentWrite, useYearSections } from "./use-enrollments-queries.ts";
import { useGradeLabels } from "./use-section-groups.ts";

export interface EnrollmentsContainerProps {
  readonly search: EnrollmentsSearch;
  readonly onSearchChange: SearchChange<EnrollmentsSearch>;
}

type DialogState =
  | { readonly kind: "closed" }
  | { readonly kind: "enroll" }
  | { readonly kind: "detail" | "transfer" | "exit"; readonly row: Enrollment };

/** The order the server applies when the address names none: by last name, ascending. */
const DEFAULT_SORT: TableSort = { columnId: "studentLastName", direction: "asc" };

/**
 * The enrollments: the list as the server serves it (filtered, ordered and paged there), the dialogs to enroll, to see the
 * detail, to move to another section and to register an exit, and the notice of the last result. The state of the table
 * is the address's. With no year in the address the server lists the year in progress, and so does the year filter show it.
 */
export function EnrollmentsContainer({ search, onSearchChange }: EnrollmentsContainerProps) {
  const { t } = useAdminText();
  const years = useYearLookup();
  const grades = useGradeLabels();
  const activeYear = years.rows.find((year) => year.status === "ACTIVE");
  const shownYearId = search.academicYearId ?? activeYear?.id;
  const sections = useYearSections(shownYearId, !years.loading);
  const list = useEnrollmentList(toApiParams(search));
  const write = useEnrollmentWrite();
  const [dialog, setDialog] = useState<DialogState>({ kind: "closed" });
  const [notice, setNotice] = useState<ResourceNotice>();
  // After an exit the row has no more actions to hold the focus: it goes to the detail of that row once the list shows it.
  const refocusId = useRef<string>(undefined);

  useEffect(() => {
    const id = refocusId.current;
    if (id === undefined || dialog.kind !== "closed") return;
    if (list.data?.items.find((row) => row.id === id)?.status === "ACTIVE") return;
    const target = document.getElementById(detailButtonId(id));
    if (target === null) return;
    target.focus();
    refocusId.current = undefined;
  }, [dialog, list.data]);

  const open = (next: DialogState) => {
    setNotice(undefined);
    setDialog(next);
  };
  const close = () => {
    setDialog({ kind: "closed" });
  };
  const done = (message: string) => {
    setDialog({ kind: "closed" });
    setNotice({ tone: "success", message });
  };

  const state = useListState({
    list,
    search,
    onSearchChange,
    filtered: search.gradeId !== undefined || search.sectionId !== undefined || search.status !== undefined || search.academicYearId !== undefined,
    cleared: { page: 1, pageSize: search.pageSize, sort: search.sort, academicYearId: undefined, gradeId: undefined, sectionId: undefined, status: undefined },
    onCreate: () => {
      open({ kind: "enroll" });
    },
    loadFailedKey: "enrollments.loadFailed",
    emptyKeys: { title: "enrollments.emptyTitle", description: "enrollments.emptyDescription", action: "enrollments.emptyAction" },
    defaultSort: DEFAULT_SORT,
  });
  const lookupsFailed = years.failed || grades.failed || sections.isError;

  return (
    <>
      <EnrollmentsView
        rows={state.rows}
        table={state.table}
        pagination={state.pagination}
        notice={notice}
        filters={{
          year: {
            value: shownYearId ?? "",
            options: optionsOf(years.rows),
            withAll: activeYear === undefined,
            // Another year has other sections, so the section filter does not outlive the change.
            onChange: (id) => {
              onSearchChange({ ...search, academicYearId: id === "" ? undefined : id, sectionId: undefined, page: 1 }, { replace: true });
            },
          },
          grade: {
            value: search.gradeId ?? "",
            options: grades.options,
            onChange: (id) => {
              onSearchChange({ ...search, gradeId: id === "" ? undefined : id, page: 1 }, { replace: true });
            },
          },
          section: {
            value: search.sectionId ?? "",
            // The same section name exists in several grades, so each option says its grade.
            options: (sections.data?.items ?? []).map((item) => ({
              value: item.id,
              label: `${grades.labels.get(item.gradeId) ?? t("organization.common.unknownReference")} · ${item.name}`,
            })),
            onChange: (id) => {
              onSearchChange({ ...search, sectionId: id === "" ? undefined : id, page: 1 }, { replace: true });
            },
          },
          status: {
            value: search.status ?? "",
            onChange: (status) => {
              onSearchChange({ ...search, status: status === "" ? undefined : status, page: 1 }, { replace: true });
            },
          },
        }}
        lookupFailure={
          lookupsFailed
            ? {
                onRetry: () => {
                  if (years.failed) years.retry();
                  if (grades.failed) grades.retry();
                  if (sections.isError) void sections.refetch();
                },
              }
            : undefined
        }
        onCreate={() => {
          open({ kind: "enroll" });
        }}
        onDetail={(row) => {
          open({ kind: "detail", row });
        }}
        onTransfer={(row) => {
          open({ kind: "transfer", row });
        }}
        onExit={(row) => {
          open({ kind: "exit", row });
        }}
      />
      {dialog.kind === "enroll" ? (
        <EnrollFormContainer
          yearId={activeYear?.id}
          yearLoading={years.loading}
          gradeLabels={grades.labels}
          save={async (request) => {
            const saved = await write(request);
            done(t("enrollments.enroll.done", { name: studentNameOf(saved) }));
          }}
          onClose={close}
        />
      ) : null}
      {dialog.kind === "detail" ? <EnrollmentDetailContainer enrollmentId={dialog.row.id} onClose={close} /> : null}
      {dialog.kind === "transfer" ? (
        <TransferFormContainer
          key={dialog.row.id}
          row={dialog.row}
          gradeLabels={grades.labels}
          save={async (request) => {
            const saved = await write(request);
            done(t("enrollments.transfer.done", { name: studentNameOf(saved), section: saved.sectionName }));
          }}
          onClose={close}
        />
      ) : null}
      {dialog.kind === "exit" ? (
        <ExitFormContainer
          key={dialog.row.id}
          row={dialog.row}
          save={async (request) => {
            const saved = await write(request);
            refocusId.current = saved.id;
            done(t("enrollments.exit.done", { name: studentNameOf(saved) }));
          }}
          onClose={close}
        />
      ) : null}
    </>
  );
}
