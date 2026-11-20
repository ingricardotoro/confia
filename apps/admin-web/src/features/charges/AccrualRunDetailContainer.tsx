import { useNavigate } from "@tanstack/react-router";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { usePermissions } from "../../shared/permissions/permissions.tsx";
import { useConceptLookup } from "../../shared/catalog-lookups/use-concept-lookup.ts";
import { nameMapOf } from "../../shared/organization-lookups/use-lookups.ts";
import { LOOKUP_PAGE_SIZE } from "../../shared/organization-lookups/use-organization-queries.ts";
import { loadFailureOf } from "../../shared/resource-screen/resource-failure.ts";
import type { SearchChange } from "../../shared/resource-screen/search-change.ts";
import { useStudentLookup } from "../../shared/student-lookups/use-student-lookup.ts";
import { AccrualRunDetailView, type AccrualRunDetailState } from "./components/AccrualRunDetailView.tsx";
import type { AccrualNames } from "./components/AccrualView.tsx";
import { chargePlanPath, CHARGES_PATHS } from "./charges-paths.ts";
import type { AccrualRunSearch } from "./charges-search.ts";
import { useAccrualRunDetail } from "./use-accrual-queries.ts";
import { useChargePlanList } from "./use-charges-queries.ts";

export interface AccrualRunDetailContainerProps {
  readonly runId: string;
  readonly search: AccrualRunSearch;
  readonly onSearchChange: SearchChange<AccrualRunSearch>;
}

/**
 * One accrual run. The query reads the run again every few seconds while it is queued or running and stops by itself once it is
 * terminal; the page of items is paged by the server and its number lives in the address. Nothing here is optimistic or computed:
 * the counters and the states are the server's.
 */
export function AccrualRunDetailContainer({ runId, search, onSearchChange }: AccrualRunDetailContainerProps) {
  const { can } = usePermissions();
  const { t } = useAdminText();
  const navigate = useNavigate();
  const detail = useAccrualRunDetail(runId, { page: search.page - 1, size: search.pageSize });
  const plans = useChargePlanList({ page: 0, size: LOOKUP_PAGE_SIZE, sort: "name,asc" });
  const students = useStudentLookup();
  const concepts = useConceptLookup();

  const unknown = t("resource.unknownReference");
  const planNames = nameMapOf(plans.data?.items ?? []);
  const studentById = new Map(students.rows.map((student) => [student.id, student]));
  const conceptById = new Map(concepts.rows.map((concept) => [concept.id, concept]));
  const names: AccrualNames = {
    student: (id) => {
      const student = studentById.get(id);
      return student === undefined
        ? unknown
        : t("charges.accrual.studentLabel", { name: `${student.lastName}, ${student.firstName}`, code: student.studentCode });
    },
    concept: (id) => {
      const concept = conceptById.get(id);
      return concept === undefined ? unknown : t("charges.accrual.conceptLabel", { code: concept.code, name: concept.name });
    },
  };
  const lookups = [students, concepts];
  const lookupFailed = lookups.some((lookup) => lookup.failed) || plans.isError;

  const data = detail.data;
  function stateOf(): AccrualRunDetailState {
    if (data !== undefined) {
      return {
        kind: "ready",
        run: data.run,
        planName: planNames.get(data.run.planId) ?? unknown,
        items: data.items,
        names,
        pagination: {
          page: data.page,
          size: data.size,
          total: data.totalItems,
          busy: false,
          onPageChange: (page) => {
            onSearchChange({ ...search, page: page + 1 });
          },
        },
        // A re-read that failed while the run was still going: the screen keeps the last answer and says so.
        refreshFailed: detail.isError,
      };
    }
    if (detail.isError) {
      const failure = loadFailureOf(detail.error);
      return {
        kind: "error",
        title: t("charges.run.loadFailed"),
        description: [t(failure.key), failure.traceId === undefined ? "" : t("resource.trace", { traceId: failure.traceId })].filter((part) => part !== "").join(" "),
        onRetry: () => void detail.refetch(),
      };
    }
    return { kind: "loading" };
  }

  const run = data?.run;
  const planHref = run === undefined ? CHARGES_PATHS.plans : chargePlanPath(run.planId);
  return (
    <AccrualRunDetailView
      state={stateOf()}
      lookupFailure={
        lookupFailed
          ? {
              onRetry: () => {
                for (const lookup of lookups) if (lookup.failed) lookup.retry();
                if (plans.isError) void plans.refetch();
              },
            }
          : undefined
      }
      backHref={CHARGES_PATHS.accrualRuns}
      onBack={() => void navigate({ to: CHARGES_PATHS.accrualRuns })}
      generationHref={CHARGES_PATHS.accrual}
      onOpenGeneration={() => void navigate({ to: CHARGES_PATHS.accrual })}
      planHref={planHref}
      onOpenPlan={() => void navigate({ to: planHref })}
      onRecalculate={
        can("charges:create")
          ? () => {
              if (run !== undefined) void navigate({ to: CHARGES_PATHS.accrual, search: { planId: run.planId, periodStart: run.periodStart } });
            }
          : undefined
      }
    />
  );
}
