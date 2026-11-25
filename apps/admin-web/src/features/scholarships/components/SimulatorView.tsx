import { Alert, Button, Card, EmptyState, ErrorState, MoneyAmount, PageHeader, Skeleton, Table, type TableColumn } from "@confia/ui";
import type { SyntheticEvent } from "react";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { OptionField, type Option } from "../../../shared/resource-screen/components/fields.tsx";
import { StatementStudentPicker, type StatementStudentPickerProps } from "../../../shared/student-lookups/components/StatementStudentPicker.tsx";
import type { ChargeSimulation, ChargeSimulationLine } from "../../../shared/api/charges-operations.ts";

const focusField = (id: string) => () => {
  document.getElementById(id)?.focus();
};

/** A select of the selection form. */
export interface SimulatorChoice {
  readonly value: string;
  readonly options: readonly Option[];
  readonly onChange: (value: string) => void;
}

export type SimulatorPlansState =
  | { readonly kind: "loading" }
  | { readonly kind: "error"; readonly description: string; readonly onRetry: () => void }
  | { readonly kind: "empty" }
  | { readonly kind: "ready" };

export type SimulatorPeriodsState =
  | { readonly kind: "idle" }
  | { readonly kind: "loading" }
  | { readonly kind: "empty" }
  | { readonly kind: "error"; readonly description: string; readonly onRetry: () => void }
  | { readonly kind: "ready" };

export type SimulatorResultState =
  | { readonly kind: "idle" }
  | { readonly kind: "loading" }
  | { readonly kind: "error"; readonly description: string; readonly onRetry: () => void }
  /** The server answered 404: the student is not enrolled in what the plan targets that period, or the plan is not there. */
  | { readonly kind: "not-enrolled" }
  | { readonly kind: "ready"; readonly simulation: ChargeSimulation; readonly planName: string; readonly periodLabel: string; readonly conceptName: (id: string) => string };

export interface SimulatorViewProps {
  readonly plans: SimulatorChoice & { readonly state: SimulatorPlansState };
  readonly periods: SimulatorChoice & { readonly state: SimulatorPeriodsState };
  /** The student: the search box while none is chosen, and the chosen one (with how to change it) afterwards. */
  readonly student: {
    readonly picker: StatementStudentPickerProps;
    readonly chosen?: { readonly label: string; readonly onClear: () => void } | undefined;
  };
  /** A plan, a student and a period are chosen, so the simulation can be asked for. */
  readonly canSimulate: boolean;
  readonly simulating: boolean;
  readonly onSimulate: () => void;
  readonly result: SimulatorResultState;
  /** Set when the concepts failed to load: their names show as not available. */
  readonly lookupFailure?: { readonly onRetry: () => void } | undefined;
  readonly onGoToPlans: () => void;
}

/**
 * The simulator of charges: choose a student, an active plan and one of its periods, and ask. The server runs the very code of the accrual engine
 * and writes nothing, and answers by concept the base, the scholarship applied, the benefit, the net base, the tax and the total, which this view draws
 * exactly as they arrived through the money component. It adds, subtracts and rounds nothing.
 */
export function SimulatorView({ plans, periods, student, canSimulate, simulating, onSimulate, result, lookupFailure, onGoToPlans }: SimulatorViewProps) {
  const { t } = useAdminText();
  const header = (
    <PageHeader title={t("scholarships.simulator.heading")} headingLevel={2} headingId="simulator-heading" description={t("scholarships.simulator.description")} />
  );

  if (plans.state.kind === "loading") {
    return (
      <section aria-labelledby="simulator-heading" aria-busy="true" className="flex flex-col gap-3">
        {header}
        <Skeleton className="h-24 w-full" />
        <Skeleton className="h-40 w-full" />
      </section>
    );
  }
  if (plans.state.kind === "error") {
    return (
      <section aria-labelledby="simulator-heading" className="flex flex-col gap-3">
        {header}
        <ErrorState title={t("scholarships.simulator.plansFailed")} description={plans.state.description} onRetry={plans.state.onRetry} />
      </section>
    );
  }
  if (plans.state.kind === "empty") {
    return (
      <section aria-labelledby="simulator-heading" className="flex flex-col gap-3">
        {header}
        <EmptyState
          title={t("scholarships.simulator.noPlansTitle")}
          description={t("scholarships.simulator.noPlansDescription")}
          action={{ label: t("scholarships.simulator.noPlansAction"), onAction: onGoToPlans }}
        />
      </section>
    );
  }

  return (
    <section aria-labelledby="simulator-heading" className="flex flex-col gap-3">
      {header}
      <form
        aria-label={t("scholarships.simulator.selectionLabel")}
        className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-bg-surface px-3 py-3"
        onSubmit={(event: SyntheticEvent) => {
          event.preventDefault();
          if (canSimulate && !simulating) onSimulate();
        }}
      >
        {student.chosen === undefined ? (
          <StatementStudentPicker {...student.picker} />
        ) : (
          <div role="group" aria-label={t("charges.statement.picker.chosen")} className="flex flex-col gap-1.5">
            <p className="text-sm font-medium text-text-primary">{t("charges.statement.picker.label")}</p>
            <div className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-border-subtle px-3 py-2">
              <p className="text-sm font-medium text-text-primary">{student.chosen.label}</p>
              <Button variant="secondary" size="sm" onClick={student.chosen.onClear}>
                {t("charges.statement.picker.change")}
              </Button>
            </div>
          </div>
        )}
        <div className="flex flex-wrap items-end gap-x-3 gap-y-2">
          <OptionField
            label={t("scholarships.simulator.plan")}
            controlId="simulator-plan"
            value={plans.value}
            options={plans.options}
            placeholder={t("scholarships.simulator.choosePlan")}
            description={t("scholarships.simulator.planHelp")}
            onChange={plans.onChange}
          />
          <OptionField
            label={t("scholarships.simulator.period")}
            controlId="simulator-period"
            value={periods.value}
            options={periods.options}
            placeholder={t("scholarships.simulator.choosePeriod")}
            description={t("scholarships.simulator.periodHelp")}
            disabled={periods.state.kind !== "ready"}
            onChange={periods.onChange}
          />
          <Button type="submit" size="sm" loading={simulating} disabled={!canSimulate}>
            {t("scholarships.simulator.simulate")}
          </Button>
        </div>
      </form>
      {periods.state.kind === "loading" ? <Skeleton className="h-9 w-full max-w-md" /> : null}
      {periods.state.kind === "error" ? (
        <Alert variant="warning">
          <p>{t("scholarships.simulator.periodsFailed")}</p>
          <p className="mt-1">{periods.state.description}</p>
          <Button variant="secondary" size="xs" className="mt-2" onClick={periods.state.onRetry}>
            {t("resource.retryOptions")}
          </Button>
        </Alert>
      ) : null}
      {periods.state.kind === "empty" ? (
        <Alert variant="info">
          <p className="font-semibold">{t("scholarships.simulator.noPeriodsTitle")}</p>
          <p>{t("scholarships.simulator.noPeriodsDescription")}</p>
        </Alert>
      ) : null}
      {lookupFailure === undefined ? null : (
        <Alert variant="warning">
          <p>{t("scholarships.simulator.lookupFailed")}</p>
          <Button variant="secondary" size="xs" className="mt-2" onClick={lookupFailure.onRetry}>
            {t("resource.retryOptions")}
          </Button>
        </Alert>
      )}
      <div aria-live="polite" aria-atomic="true" className="empty:hidden">
        {result.kind === "idle" ? (
          <EmptyState
            title={t("scholarships.simulator.idleTitle")}
            description={t("scholarships.simulator.idleDescription")}
            action={{ label: t("scholarships.simulator.idleAction"), onAction: focusField("simulator-plan") }}
          />
        ) : null}
        {result.kind === "loading" ? (
          <div role="status" aria-busy="true" className="flex flex-col gap-2">
            <span className="sr-only">{t("scholarships.simulator.simulating")}</span>
            <Skeleton className="h-28 w-full" />
            <Skeleton className="h-40 w-full" />
          </div>
        ) : null}
        {result.kind === "error" ? <ErrorState title={t("scholarships.simulator.simulationFailed")} description={result.description} onRetry={result.onRetry} /> : null}
        {result.kind === "not-enrolled" ? (
          <Alert variant="warning">
            <p className="font-medium">{t("scholarships.simulator.notEnrolledTitle")}</p>
            <p className="mt-1">{t("scholarships.simulator.notEnrolledDescription")}</p>
          </Alert>
        ) : null}
        {result.kind === "ready" ? <SimulationResult state={result} /> : null}
      </div>
    </section>
  );
}

function SimulationResult({ state }: { readonly state: Extract<SimulatorResultState, { kind: "ready" }> }) {
  const { t } = useAdminText();
  const { simulation, conceptName } = state;
  const noAmount = t("scholarships.simulator.noAmount");
  const amount = (value: ChargeSimulationLine["base"], strong = false) => (value === undefined ? noAmount : <MoneyAmount value={value} size="sm" emphasis={strong ? "strong" : "normal"} />);

  const columns: readonly TableColumn<ChargeSimulationLine>[] = [
    { id: "concept", header: t("scholarships.simulator.columns.concept"), cell: (line) => conceptName(line.paymentConceptId) },
    { id: "base", header: t("scholarships.simulator.columns.base"), align: "end", cell: (line) => amount(line.base) },
    {
      id: "scholarship",
      header: t("scholarships.simulator.columns.scholarship"),
      cell: (line) => (line.scholarshipCode == null ? t("scholarships.simulator.noScholarship") : line.scholarshipCode),
    },
    { id: "benefit", header: t("scholarships.simulator.columns.benefit"), align: "end", cell: (line) => amount(line.benefitAmount) },
    { id: "netBase", header: t("scholarships.simulator.columns.netBase"), align: "end", cell: (line) => amount(line.netBase) },
    { id: "tax", header: t("scholarships.simulator.columns.tax"), align: "end", cell: (line) => amount(line.tax) },
    { id: "total", header: t("scholarships.simulator.columns.total"), align: "end", cell: (line) => amount(line.total, true) },
    {
      id: "result",
      header: t("scholarships.simulator.columns.result"),
      cell: (line) =>
        line.outcome === "ERROR"
          ? t("scholarships.simulator.outcomes.ERROR", { code: line.errorCode ?? "" })
          : t(`scholarships.simulator.outcomes.${line.outcome}`),
    },
  ];

  return (
    <Card title={t("scholarships.simulator.resultTitle")} description={t("scholarships.simulator.resultDescription", { plan: state.planName, period: state.periodLabel })}>
      <div className="flex flex-col gap-3">
        <Alert variant="info">
          <p>{t("scholarships.simulator.nothingWritten")}</p>
        </Alert>
        <Table
          caption={t("scholarships.simulator.caption")}
          captionHidden
          density="compact"
          columns={columns}
          rows={simulation.lines}
          getRowId={(line) => line.paymentConceptId}
          empty={{
            title: t("scholarships.simulator.emptyTitle"),
            description: t("scholarships.simulator.emptyDescription"),
            action: { label: t("scholarships.simulator.emptyAction"), onAction: focusField("simulator-period") },
          }}
        />
      </div>
    </Card>
  );
}
