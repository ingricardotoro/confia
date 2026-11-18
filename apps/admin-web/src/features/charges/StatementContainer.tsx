import { useState } from "react";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { ApiFailure } from "../../shared/api/api-failure.ts";
import { loadFailureOf } from "../../shared/resource-screen/resource-failure.ts";
import { useDebouncedSearch } from "../../shared/resource-screen/use-debounced-search.ts";
import { useStudentSearch } from "../../shared/student-lookups/use-student-search.ts";
import { StatementView, type StatementState } from "./components/StatementView.tsx";
import type { StatementStudentSearchState } from "./components/StatementStudentPicker.tsx";
import { STATEMENT_PAGE_SIZE, type StatementSearch } from "./charges-search.ts";
import { currencyNameOf, periodProblemOf } from "./statement-model.ts";
import { useStudentStatement } from "./use-statement-queries.ts";

export interface StatementContainerProps {
  readonly search: StatementSearch;
  readonly onSearchChange: (search: StatementSearch) => void;
}

/** The currencies the student has, remembered from the last read that was not narrowed to one: the selector must outlive the narrowing. */
interface KnownCurrencies {
  readonly studentId: string;
  readonly currencies: readonly string[];
}

/**
 * The statement of account. The student, the currency, the period and the page are in the address, so a link (from the students
 * registry, say) opens it already pointed at one and the view can be shared. The server derives every balance and the running
 * balance of every line from the ledger and pages the lines; nothing here is optimistic, kept apart from the query cache, or
 * computed. A period that ends before it starts is told on the field and never sent.
 */
export function StatementContainer({ search, onSearchChange }: StatementContainerProps) {
  const { t } = useAdminText();
  const box = useDebouncedSearch();
  const found = useStudentSearch(box.text);
  const [known, setKnown] = useState<KnownCurrencies>();

  const periodProblem = periodProblemOf(search.from, search.to);
  const statement = useStudentStatement(periodProblem === undefined ? search.studentId : undefined, {
    currency: search.currency,
    from: search.from,
    to: search.to,
    page: search.page - 1,
    size: STATEMENT_PAGE_SIZE,
  });
  const data = statement.data;

  // The currencies are learned from a read that was not narrowed, and kept while the person narrows to one.
  if (data !== undefined && search.currency === undefined) {
    const currencies = data.sections.map((section) => section.currency);
    if (known?.studentId !== data.studentId || known.currencies.join() !== currencies.join()) setKnown({ studentId: data.studentId, currencies });
  }
  const currencies = known !== undefined && known.studentId === search.studentId ? known.currencies : [];

  const change = (next: Partial<StatementSearch>) => {
    onSearchChange({ ...search, page: 1, ...next });
  };

  const searchState: StatementStudentSearchState =
    box.waiting ? "waiting" : found.isFetching ? "loading" : found.isError ? "failed" : found.data !== undefined && box.text !== undefined ? "ready" : "idle";

  function stateOf(): StatementState {
    if (search.studentId === undefined) return { kind: "idle" };
    if (periodProblem !== undefined) return { kind: "invalid-period" };
    if (data !== undefined) {
      if (data.sections.length === 0) return { kind: "empty" };
      return { kind: "ready", statement: data, busy: statement.isPlaceholderData };
    }
    if (statement.isError) {
      const status = statement.error instanceof ApiFailure && statement.error.result.kind === "problem" ? statement.error.result.problem.status : undefined;
      if (status === 404) return { kind: "blocked", title: t("charges.statement.notFoundTitle"), description: t("charges.statement.notFoundDescription") };
      if (status === 403) return { kind: "blocked", title: t("charges.statement.forbiddenTitle"), description: t("charges.statement.forbiddenDescription") };
      const failure = loadFailureOf(statement.error);
      return {
        kind: "error",
        title: t("charges.statement.loadFailed"),
        description: [t(failure.key), failure.traceId === undefined ? "" : t("resource.trace", { traceId: failure.traceId })].filter((part) => part !== "").join(" "),
        onRetry: () => void statement.refetch(),
      };
    }
    return { kind: "loading" };
  }

  const chosen = data !== undefined && data.studentId === search.studentId ? { id: data.studentId, studentCode: data.studentCode, firstName: data.firstName, lastName: data.lastName } : undefined;

  return (
    <StatementView
      state={stateOf()}
      picker={{
        controlId: "statement-student",
        draft: box.draft,
        onDraftChange: box.setDraft,
        tooShort: box.tooShort,
        search: searchState,
        results: found.data?.items ?? [],
        onRetry: () => void found.refetch(),
        chosen,
        chosenPending: search.studentId !== undefined,
        onChoose: (student) => {
          box.setDraft("");
          change({ studentId: student.id, currency: undefined });
        },
        onClear: () => {
          box.setDraft("");
          change({ studentId: undefined, currency: undefined });
        },
      }}
      period={{
        from: search.from ?? "",
        to: search.to ?? "",
        toError: periodProblem === undefined ? undefined : t("charges.statement.errors.fromAfterTo"),
        onFromChange: (from) => {
          change({ from: from === "" ? undefined : from });
        },
        onToChange: (to) => {
          change({ to: to === "" ? undefined : to });
        },
      }}
      currency={
        currencies.length > 1
          ? {
              value: search.currency ?? "",
              options: currencies.map((code) => ({ value: code, label: t("charges.statement.currencyOption", { currency: currencyNameOf(code), code }) })),
              onChange: (code) => {
                change({ currency: currencies.find((known) => known === code) });
              },
            }
          : undefined
      }
      onPageChange={(page) => {
        onSearchChange({ ...search, page: page + 1 });
      }}
    />
  );
}
