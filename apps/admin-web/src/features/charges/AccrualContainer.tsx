import { useNavigate } from "@tanstack/react-router";
import { useState } from "react";
import type { AdminKey } from "../../i18n/resources.ts";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { ApiFailure } from "../../shared/api/api-failure.ts";
import { useConceptLookup } from "../../shared/catalog-lookups/use-concept-lookup.ts";
import { formatIsoDate } from "../../shared/format/format-date.ts";
import { LOOKUP_PAGE_SIZE } from "../../shared/organization-lookups/use-organization-queries.ts";
import { failureMessageOf, failureOf, loadFailureOf } from "../../shared/resource-screen/resource-failure.ts";
import { useStudentLookup } from "../../shared/student-lookups/use-student-lookup.ts";
import { newIntent, periodLabelOf, previewMatches, type AccrualIntent } from "./accrual-model.ts";
import { AccrualConfirmDialog } from "./components/AccrualConfirmDialog.tsx";
import { AccrualView, type AccrualNames, type AccrualPeriodsState, type AccrualResultState } from "./components/AccrualView.tsx";
import { accrualRunPath, chargePlanPath, CHARGES_PATHS } from "./charges-paths.ts";
import type { AccrualSearch } from "./charges-search.ts";
import { useAccrualPeriods, useAccrualPreview, useAccrualRunRequest } from "./use-accrual-queries.ts";
import { useChargePlanList } from "./use-charges-queries.ts";

export interface AccrualContainerProps {
  readonly search: AccrualSearch;
  readonly onSearchChange: (search: AccrualSearch) => void;
}

/** The failure of the last try of a confirmation, ready for the dialog. */
interface ConfirmFailure {
  readonly message: string;
  readonly traceId?: string | undefined;
  /** The server may not have received the request (the network failed or the answer made no sense): the same intent can be sent again. */
  readonly retryable: boolean;
}

/**
 * The generation of charges. The plan and the period are in the address, so a link opens the page already pointed at what to
 * generate; the preview is asked for on demand and lives only while the choice it was made for stands. Confirming opens an explicit
 * dialog; the confirmation is one intent with one `Idempotency-Key`, sent again unchanged if the answer never arrived, and the
 * run it queues is followed on its own page. Nothing here adds, rounds or compares an amount.
 */
export function AccrualContainer({ search, onSearchChange }: AccrualContainerProps) {
  const { t } = useAdminText();
  const navigate = useNavigate();
  const plans = useChargePlanList({ page: 0, size: LOOKUP_PAGE_SIZE, sort: "name,asc", status: "ACTIVE" });
  const students = useStudentLookup();
  const concepts = useConceptLookup();
  const preview = useAccrualPreview();
  const request = useAccrualRunRequest();
  const [intent, setIntent] = useState<AccrualIntent>();
  const [failure, setFailure] = useState<ConfirmFailure>();

  const activePlans = plans.data?.items ?? [];
  // A plan or a period in the address that is not one of the choices is not a choice: the selects show their placeholder.
  const planId = activePlans.find((plan) => plan.id === search.planId)?.id;
  const periods = useAccrualPeriods(planId);
  const periodRows = periods.data ?? [];
  const periodStart = periodRows.find((period) => period.periodStart === search.periodStart)?.periodStart;
  const selection = planId !== undefined && periodStart !== undefined ? { planId, periodStart } : undefined;
  const plan = activePlans.find((row) => row.id === planId);

  const unknown = t("resource.unknownReference");
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
  const lookupFailure = lookups.some((lookup) => lookup.failed)
    ? {
        onRetry: () => {
          for (const lookup of lookups) if (lookup.failed) lookup.retry();
        },
      }
    : undefined;

  const traceText = (failed: { key: AdminKey; traceId?: string | undefined }) =>
    [t(failed.key), failed.traceId === undefined ? "" : t("resource.trace", { traceId: failed.traceId })].filter((part) => part !== "").join(" ");

  function periodsState(): AccrualPeriodsState {
    if (planId === undefined) return { kind: "idle" };
    if (periods.isError) return { kind: "error", description: traceText(loadFailureOf(periods.error)), onRetry: () => void periods.refetch() };
    if (periods.isPending) return { kind: "loading" };
    return periodRows.length === 0 ? { kind: "empty" } : { kind: "ready" };
  }

  function resultState(): AccrualResultState {
    if (preview.isPending) return { kind: "loading" };
    if (preview.isError) {
      return {
        kind: "error",
        description: traceText(loadFailureOf(preview.error)),
        onRetry: () => {
          if (selection !== undefined) preview.mutate(selection);
        },
      };
    }
    // A preview of another choice is not shown: the choice changed after it was calculated.
    if (preview.data !== undefined && plan !== undefined && previewMatches(preview.data, selection ?? {})) {
      return {
        kind: "ready",
        preview: preview.data,
        planName: plan.name,
        periodLabel: periodLabelOf(preview.data.periodStart),
        planHref: chargePlanPath(plan.id),
        names,
      };
    }
    return { kind: "idle" };
  }

  const choose = (next: AccrualSearch) => {
    preview.reset();
    setIntent(undefined);
    setFailure(undefined);
    onSearchChange(next);
  };

  async function send(current: AccrualIntent) {
    // One try at a time: a second press while the first is in flight sends nothing.
    if (request.isPending) return;
    setFailure(undefined);
    try {
      const run = await request.mutateAsync(current);
      setIntent(undefined);
      await navigate({ to: accrualRunPath(run.id) });
    } catch (error) {
      const message = failureMessageOf(failureOf(error));
      const unsure = error instanceof ApiFailure && (error.result.kind === "network" || error.result.kind === "unexpected-response");
      setFailure({ message: t(message.key), traceId: message.traceId, retryable: unsure });
    }
  }

  const result = resultState();
  const planState = plans.isError
    ? ({ kind: "error", description: traceText(loadFailureOf(plans.error)), onRetry: () => void plans.refetch() } as const)
    : plans.isPending
      ? ({ kind: "loading" } as const)
      : activePlans.length === 0
        ? ({ kind: "empty" } as const)
        : ({ kind: "ready" } as const);

  return (
    <>
      <AccrualView
        plans={{
          state: planState,
          value: planId ?? "",
          options: activePlans.map((row) => ({ value: row.id, label: row.name })),
          onChange: (next) => {
            choose({ planId: activePlans.find((row) => row.id === next)?.id, periodStart: undefined });
          },
        }}
        periods={{
          state: periodsState(),
          value: periodStart ?? "",
          options: periodRows.map((period) => ({
            value: period.periodStart,
            label:
              period.lastRun == null
                ? t("charges.accrual.periodOption", { label: period.label, dueOn: formatIsoDate(period.dueOn) })
                : t("charges.accrual.periodOptionRun", {
                    label: period.label,
                    dueOn: formatIsoDate(period.dueOn),
                    status: t(`charges.accrual.statuses.${period.lastRun.status}`),
                  }),
          })),
          onChange: (next) => {
            choose({ planId, periodStart: periodRows.find((period) => period.periodStart === next)?.periodStart });
          },
        }}
        canPreview={selection !== undefined}
        previewing={preview.isPending}
        onPreview={() => {
          if (selection !== undefined && !preview.isPending) preview.mutate(selection);
        }}
        result={result}
        lookupFailure={lookupFailure}
        onConfirm={() => {
          if (selection === undefined || preview.data === undefined) return;
          // A new confirmation is a new intent, with its own key.
          setFailure(undefined);
          setIntent(newIntent(selection, preview.data));
        }}
        onViewRuns={() => void navigate({ to: CHARGES_PATHS.accrualRuns })}
        onOpenPlan={() => {
          if (planId !== undefined) void navigate({ to: chargePlanPath(planId) });
        }}
        onGoToPlans={() => void navigate({ to: CHARGES_PATHS.plans })}
      />
      {intent !== undefined && result.kind === "ready" ? (
        <AccrualConfirmDialog
          planName={result.planName}
          periodLabel={result.periodLabel}
          preview={result.preview}
          busy={request.isPending}
          failure={failure}
          onConfirm={() => void send(intent)}
          onCancel={() => {
            setIntent(undefined);
            setFailure(undefined);
          }}
        />
      ) : null}
    </>
  );
}
