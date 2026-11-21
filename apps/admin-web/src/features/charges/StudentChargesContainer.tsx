import { useNavigate } from "@tanstack/react-router";
import { useState } from "react";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { ApiFailure } from "../../shared/api/api-failure.ts";
import { STATEMENT_PATH } from "../../shared/navigation/statement-paths.ts";
import type { ResourceNotice } from "../../shared/resource-screen/components/ResourceScreen.tsx";
import { toApiParams } from "../../shared/resource-screen/list-search.ts";
import type { SearchChange } from "../../shared/resource-screen/search-change.ts";
import { useDebouncedSearch } from "../../shared/resource-screen/use-debounced-search.ts";
import { useListState } from "../../shared/resource-screen/use-list-state.ts";
import { useStudentSearch } from "../../shared/student-lookups/use-student-search.ts";
import type { StatementStudentSearchState } from "../../shared/student-lookups/components/StatementStudentPicker.tsx";
import { StudentChargesView, type StudentChargesState } from "./components/StudentChargesView.tsx";
import type { StudentChargesSearch } from "./charges-search.ts";
import type { StudentCharge } from "./charges-types.ts";
import { periodProblemOf } from "./statement-model.ts";
import { useStudentChargeList } from "./use-student-charge-queries.ts";
import { VoidChargeContainer } from "./VoidChargeContainer.tsx";

export interface StudentChargesContainerProps {
  readonly search: StudentChargesSearch;
  readonly onSearchChange: SearchChange<StudentChargesSearch>;
}

/** The student chosen in this visit: the address only holds the id, so the name is remembered while it is the same student. */
interface NamedStudent {
  readonly id: string;
  readonly label: string;
}

const NO_FILTERS = { status: undefined, periodFrom: undefined, periodTo: undefined, sort: undefined } as const;

/**
 * The charges of a student. The student, the status, the period, the order and the page are in the address, so a link (from the
 * statement of account or the students registry) opens it already pointed at one and the view can be shared. The server pages,
 * filters and orders; the status of each charge is the server's. Voiding opens an explicit dialog; when the server confirms,
 * the list and the statements are read again and the person is told.
 */
export function StudentChargesContainer({ search, onSearchChange }: StudentChargesContainerProps) {
  const { t } = useAdminText();
  const navigate = useNavigate();
  const box = useDebouncedSearch();
  const found = useStudentSearch(box.text);
  const [named, setNamed] = useState<NamedStudent>();
  const [target, setTarget] = useState<StudentCharge>();
  const [notice, setNotice] = useState<ResourceNotice>();

  const periodProblem = periodProblemOf(search.periodFrom, search.periodTo);
  const { studentId, ...listSearch } = search;
  const list = useStudentChargeList(periodProblem === undefined ? studentId : undefined, toApiParams(listSearch));
  const filtered = search.status !== undefined || search.periodFrom !== undefined || search.periodTo !== undefined;
  const goToStatement = () => {
    if (studentId !== undefined) void navigate({ to: STATEMENT_PATH, search: { studentId } });
  };
  const state = useListState({
    list,
    search,
    onSearchChange,
    filtered,
    cleared: { ...search, page: 1, status: undefined, periodFrom: undefined, periodTo: undefined },
    onCreate: goToStatement,
    loadFailedKey: "charges.studentCharges.loadFailed",
    emptyKeys: { title: "charges.studentCharges.emptyTitle", description: "charges.studentCharges.emptyDescription", action: "charges.studentCharges.emptyAction" },
    defaultSort: { columnId: "periodStart", direction: "desc" },
  });

  const change = (next: Partial<StudentChargesSearch>) => {
    setNotice(undefined);
    onSearchChange({ ...search, page: 1, ...next }, { replace: true });
  };

  function stateOf(): StudentChargesState {
    if (studentId === undefined) return { kind: "idle" };
    if (periodProblem !== undefined) return { kind: "invalid-period" };
    if (list.isError && list.data === undefined) {
      const status = list.error instanceof ApiFailure && list.error.result.kind === "problem" ? list.error.result.problem.status : undefined;
      if (status === 404) return { kind: "blocked", title: t("charges.studentCharges.notFoundTitle"), description: t("charges.studentCharges.notFoundDescription") };
      if (status === 403) return { kind: "blocked", title: t("charges.studentCharges.forbiddenTitle"), description: t("charges.studentCharges.forbiddenDescription") };
    }
    return { kind: "ready" };
  }

  const searchState: StatementStudentSearchState =
    box.waiting ? "waiting" : found.isFetching ? "loading" : found.isError ? "failed" : found.data !== undefined && box.text !== undefined ? "ready" : "idle";

  return (
    <>
      <StudentChargesView
        state={stateOf()}
        picker={{
          controlId: "student-charges-student",
          draft: box.draft,
          onDraftChange: box.setDraft,
          tooShort: box.tooShort,
          search: searchState,
          results: found.data?.items ?? [],
          onRetry: () => void found.refetch(),
          chosen: undefined,
          chosenPending: false,
          onChoose: (student) => {
            box.setDraft("");
            setNamed({ id: student.id, label: t("charges.statement.picker.optionText", { name: `${student.lastName}, ${student.firstName}`, code: student.studentCode }) });
            change({ studentId: student.id, ...NO_FILTERS });
          },
          onClear: () => undefined,
        }}
        chosen={
          studentId === undefined
            ? undefined
            : {
                label: named?.id === studentId ? named.label : undefined,
                onChange: () => {
                  change({ studentId: undefined, ...NO_FILTERS });
                },
                onViewStatement: goToStatement,
              }
        }
        filters={{
          status: {
            value: search.status ?? "",
            onChange: (status) => {
              change({ status: status === "ACTIVE" || status === "VOIDED" ? status : undefined });
            },
          },
          from: {
            value: search.periodFrom ?? "",
            onChange: (from) => {
              change({ periodFrom: from === "" ? undefined : from });
            },
          },
          to: {
            value: search.periodTo ?? "",
            error: periodProblem === undefined ? undefined : t("charges.studentCharges.errors.fromAfterTo"),
            onChange: (to) => {
              change({ periodTo: to === "" ? undefined : to });
            },
          },
        }}
        rows={state.rows}
        table={state.table}
        pagination={state.pagination}
        notice={notice}
        onVoid={(row) => {
          setNotice(undefined);
          setTarget(row);
        }}
      />
      {target === undefined ? null : (
        <VoidChargeContainer
          // A new dialog for each charge, so nothing typed for one is shown for another.
          key={target.id}
          charge={target}
          onClose={() => {
            setTarget(undefined);
          }}
          onVoided={() => {
            setNotice({
              tone: "success",
              message: t("charges.studentCharges.voidedNotice", {
                concept: target.conceptName ?? target.conceptCode ?? t("charges.studentCharges.noConcept"),
                period: target.periodLabel,
              }),
            });
            setTarget(undefined);
          }}
        />
      )}
    </>
  );
}
