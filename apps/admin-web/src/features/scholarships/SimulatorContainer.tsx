import { useNavigate } from "@tanstack/react-router";
import { useState } from "react";
import type { AdminKey } from "../../i18n/resources.ts";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { ApiFailure } from "../../shared/api/api-failure.ts";
import { useConceptLookup } from "../../shared/catalog-lookups/use-concept-lookup.ts";
import { formatIsoDate } from "../../shared/format/format-date.ts";
import { CHARGE_PLANS_PATH } from "../../shared/navigation/charge-plans-paths.ts";
import { loadFailureOf } from "../../shared/resource-screen/resource-failure.ts";
import { useDebouncedSearch } from "../../shared/resource-screen/use-debounced-search.ts";
import { useStudentSearch } from "../../shared/student-lookups/use-student-search.ts";
import type { StatementStudentSearchState } from "../../shared/student-lookups/components/StatementStudentPicker.tsx";
import { SimulatorView, type SimulatorPeriodsState, type SimulatorPlansState, type SimulatorResultState } from "./components/SimulatorView.tsx";
import type { SimulatorSearch } from "./scholarships-search.ts";
import { useActivePlans, useChargeSimulation, useSimulatorPeriods } from "./use-simulator-queries.ts";

export interface SimulatorContainerProps {
  readonly search: SimulatorSearch;
  readonly onSearchChange: (search: SimulatorSearch) => void;
}

/** The student chosen in this visit: the address only holds the id, so the name is remembered while it is the same student. */
interface NamedStudent {
  readonly id: string;
  readonly label: string;
}

/**
 * The simulator of charges. The plan, the student and the period are in the address, so a link opens the page already pointed at what to simulate;
 * the simulation is asked for on demand and lives only while the choice it was made for stands. Nothing here adds, rounds or compares an amount:
 * the server runs the accrual engine and answers the figures.
 */
export function SimulatorContainer({ search, onSearchChange }: SimulatorContainerProps) {
  const { t } = useAdminText();
  const navigate = useNavigate();
  const plans = useActivePlans();
  const concepts = useConceptLookup();
  const simulation = useChargeSimulation();
  const box = useDebouncedSearch();
  const found = useStudentSearch(box.text);
  const [named, setNamed] = useState<NamedStudent>();

  const activePlans = plans.data?.items ?? [];
  // A plan or a period in the address that is not one of the choices is not a choice: the selects show their placeholder.
  const planId = activePlans.find((plan) => plan.id === search.planId)?.id;
  const periods = useSimulatorPeriods(planId);
  const periodRows = periods.data ?? [];
  const period = periodRows.find((row) => row.periodStart === search.period);
  const studentId = search.studentId;
  const selection = planId !== undefined && studentId !== undefined && period !== undefined ? { planId, studentId, period: period.periodStart } : undefined;
  const plan = activePlans.find((row) => row.id === planId);

  const unknown = t("resource.unknownReference");
  const conceptById = new Map(concepts.rows.map((concept) => [concept.id, concept]));
  const conceptName = (id: string): string => {
    const concept = conceptById.get(id);
    return concept === undefined ? unknown : t("scholarships.simulator.conceptLabel", { code: concept.code, name: concept.name });
  };

  const traceText = (failed: { key: AdminKey; traceId?: string | undefined }) =>
    [t(failed.key), failed.traceId === undefined ? "" : t("resource.trace", { traceId: failed.traceId })].filter((part) => part !== "").join(" ");

  const plansState: SimulatorPlansState = plans.isError
    ? { kind: "error", description: traceText(loadFailureOf(plans.error)), onRetry: () => void plans.refetch() }
    : plans.isPending
      ? { kind: "loading" }
      : activePlans.length === 0
        ? { kind: "empty" }
        : { kind: "ready" };

  const periodsState: SimulatorPeriodsState =
    planId === undefined
      ? { kind: "idle" }
      : periods.isError
        ? { kind: "error", description: traceText(loadFailureOf(periods.error)), onRetry: () => void periods.refetch() }
        : periods.isPending
          ? { kind: "loading" }
          : periodRows.length === 0
            ? { kind: "empty" }
            : { kind: "ready" };

  /** The 404 of the simulator says the student is not enrolled in what the plan targets that period: an answer, not a failure. */
  const notEnrolled =
    simulation.isError && simulation.error instanceof ApiFailure && simulation.error.result.kind === "problem" && simulation.error.result.problem.status === 404;

  function resultState(): SimulatorResultState {
    if (simulation.isPending) return { kind: "loading" };
    if (notEnrolled) return { kind: "not-enrolled" };
    if (simulation.isError) {
      return {
        kind: "error",
        description: traceText(loadFailureOf(simulation.error)),
        onRetry: () => {
          if (selection !== undefined) simulation.mutate(selection);
        },
      };
    }
    // A simulation of another choice is not shown: the choice changed after it was calculated.
    const data = simulation.data;
    if (data !== undefined && plan !== undefined && selection !== undefined && data.planId === selection.planId && data.studentId === selection.studentId && data.periodStart === selection.period) {
      return { kind: "ready", simulation: data, planName: plan.name, periodLabel: data.periodLabel, conceptName };
    }
    return { kind: "idle" };
  }

  const choose = (next: SimulatorSearch) => {
    simulation.reset();
    onSearchChange(next);
  };
  const searchState: StatementStudentSearchState =
    box.waiting ? "waiting" : found.isFetching ? "loading" : found.isError ? "failed" : found.data !== undefined && box.text !== undefined ? "ready" : "idle";

  return (
    <SimulatorView
      plans={{
        state: plansState,
        value: planId ?? "",
        options: activePlans.map((row) => ({ value: row.id, label: row.name })),
        onChange: (next) => {
          choose({ planId: activePlans.find((row) => row.id === next)?.id, studentId, period: undefined });
        },
      }}
      periods={{
        state: periodsState,
        value: period?.periodStart ?? "",
        options: periodRows.map((row) => ({ value: row.periodStart, label: t("scholarships.simulator.periodOption", { label: row.label, dueOn: formatIsoDate(row.dueOn) }) })),
        onChange: (next) => {
          choose({ planId, studentId, period: periodRows.find((row) => row.periodStart === next)?.periodStart });
        },
      }}
      student={{
        picker: {
          controlId: "simulator-student",
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
            choose({ planId, studentId: student.id, period: search.period });
          },
          onClear: () => undefined,
        },
        chosen:
          studentId === undefined
            ? undefined
            : {
                label: named?.id === studentId ? named.label : t("scholarships.awards.studentUnnamed"),
                onClear: () => {
                  choose({ planId, studentId: undefined, period: search.period });
                },
              },
      }}
      canSimulate={selection !== undefined}
      simulating={simulation.isPending}
      onSimulate={() => {
        if (selection !== undefined && !simulation.isPending) simulation.mutate(selection);
      }}
      result={resultState()}
      lookupFailure={concepts.failed ? { onRetry: concepts.retry } : undefined}
      onGoToPlans={() => void navigate({ to: CHARGE_PLANS_PATH })}
    />
  );
}
