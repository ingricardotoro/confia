import { Alert, Breadcrumb, Button, Card, ErrorState, KeyFigure, PageHeader, Pagination, Skeleton, Table, type TableColumn } from "@confia/ui";
import type { ReactNode } from "react";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import type { AccrualRun, AccrualRunItem } from "../charges-types.ts";
import { formatInstant } from "../../../shared/format/format-date.ts";
import type { ResourcePagination } from "../../../shared/resource-screen/components/ResourceScreen.tsx";
import { failureKeyOf, isActiveRun, itemErrorHelpKeyOf, periodLabelOf } from "../accrual-model.ts";
import { AccrualOutcomeBadge } from "./AccrualOutcomeBadge.tsx";
import { AccrualRunStatusBadge } from "./AccrualRunStatusBadge.tsx";
import type { AccrualNames } from "./AccrualView.tsx";

export type AccrualRunDetailState =
  | { readonly kind: "loading" }
  | { readonly kind: "error"; readonly title: string; readonly description: string; readonly onRetry: () => void }
  | {
      readonly kind: "ready";
      readonly run: AccrualRun;
      readonly planName: string;
      readonly items: readonly AccrualRunItem[];
      readonly names: AccrualNames;
      readonly pagination: ResourcePagination;
      /** The last re-read of a run still going failed: what is on screen is the last answer the server gave. */
      readonly refreshFailed: boolean;
    };

export interface AccrualRunDetailViewProps {
  readonly state: AccrualRunDetailState;
  /** Set when the lists that name the students and the concepts failed to load: names show as not available. */
  readonly lookupFailure?: { readonly onRetry: () => void } | undefined;
  readonly backHref: string;
  readonly onBack: () => void;
  /** The address of the generation page, and the navigation to it. */
  readonly generationHref: string;
  readonly onOpenGeneration: () => void;
  readonly planHref: string;
  readonly onOpenPlan: () => void;
  /** Calculate the preview again for the plan and the period of this run. */
  readonly onRecalculate: () => void;
}

function Summary({ term, children }: { readonly term: string; readonly children: ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="text-xs font-semibold tracking-caps text-text-secondary uppercase">{term}</dt>
      <dd className="text-sm font-medium text-text-primary">{children}</dd>
    </div>
  );
}

function RunItems({ state, onBack }: { readonly state: Extract<AccrualRunDetailState, { kind: "ready" }>; readonly onBack: () => void }) {
  const { t } = useAdminText();
  const { run, items, names, pagination } = state;
  const columns: readonly TableColumn<AccrualRunItem>[] = [
    { id: "student", header: t("charges.run.items.student"), cell: (item) => names.student(item.studentId) },
    { id: "concept", header: t("charges.run.items.concept"), cell: (item) => names.concept(item.paymentConceptId) },
    { id: "result", header: t("charges.run.items.result"), cell: (item) => <AccrualOutcomeBadge mode="run" outcome={item.outcome} errorCode={item.errorCode} /> },
    {
      id: "detail",
      header: t("charges.run.items.detail"),
      cell: (item) => (item.outcome === "ERROR" ? t(itemErrorHelpKeyOf(item.errorCode)) : t("charges.run.items.noDetail")),
    },
  ];
  const pending = isActiveRun(run.status);

  return (
    <section aria-labelledby="accrual-run-items-heading" className="flex flex-col gap-2">
      <h3 id="accrual-run-items-heading" className="text-base font-semibold text-text-primary">
        {t("charges.run.items.heading")}
      </h3>
      <Table
        caption={t("charges.run.items.caption")}
        captionHidden
        density="compact"
        columns={columns}
        rows={items}
        getRowId={(item) => `${item.studentId}:${item.paymentConceptId}`}
        empty={
          pending
            ? { title: t("charges.run.items.emptyPendingTitle"), description: t("charges.run.items.emptyPendingDescription"), action: { label: t("charges.run.back"), onAction: onBack } }
            : { title: t("charges.run.items.emptyTitle"), description: t("charges.run.items.emptyDescription"), action: { label: t("charges.run.back"), onAction: onBack } }
        }
      />
      <Pagination
        page={pagination.page}
        size={pagination.size}
        total={pagination.total}
        onPageChange={pagination.onPageChange}
        disabled={pagination.busy}
        label={t("charges.run.items.pagination")}
      />
    </section>
  );
}

/**
 * One accrual run: its state with a dot, its counters, who asked and when, and what happened to each student and concept. While the
 * run is queued or running the region that tells its progress is a live region, so a screen reader hears each change; the page
 * is read again by the container until the run is terminal. The view draws what the server holds and counts nothing itself.
 */
export function AccrualRunDetailView({ state, lookupFailure, backHref, onBack, generationHref, onOpenGeneration, planHref, onOpenPlan, onRecalculate }: AccrualRunDetailViewProps) {
  const { t } = useAdminText();

  const back = (
    <a
      href={backHref}
      className="w-fit text-sm text-text-primary underline underline-offset-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-border-focus"
      onClick={(event) => {
        if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
        event.preventDefault();
        onBack();
      }}
    >
      {t("charges.run.back")}
    </a>
  );

  if (state.kind === "loading") {
    return (
      <section aria-labelledby="accrual-run-heading" aria-busy="true" className="flex flex-col gap-3">
        {back}
        <h2 id="accrual-run-heading" className="sr-only">
          {t("charges.runs.heading")}
        </h2>
        <Skeleton className="h-7 w-72" />
        <Skeleton className="h-16 w-full max-w-xl" />
        <Skeleton className="h-40 w-full" />
      </section>
    );
  }

  if (state.kind === "error") {
    return (
      <section aria-labelledby="accrual-run-heading" className="flex flex-col gap-3">
        {back}
        <h2 id="accrual-run-heading" className="sr-only">
          {t("charges.runs.heading")}
        </h2>
        <ErrorState title={state.title} description={state.description} onRetry={state.onRetry} />
      </section>
    );
  }

  const { run, planName } = state;
  const period = periodLabelOf(run.periodStart);
  const rejected = run.status === "REJECTED";
  const failed = run.status === "FAILED";

  return (
    <section aria-labelledby="accrual-run-heading" className="flex flex-col gap-3">
      <PageHeader
        title={t("charges.run.title", { plan: planName, period })}
        headingLevel={2}
        headingId="accrual-run-heading"
        breadcrumb={
          <Breadcrumb
            label={t("shell.breadcrumbLabel")}
            items={[
              { id: "generation", label: t("charges.accrual.heading"), href: generationHref, onNavigate: onOpenGeneration },
              { id: "runs", label: t("charges.runs.heading"), href: backHref, onNavigate: onBack },
              { id: "run", label: period },
            ]}
          />
        }
        status={<AccrualRunStatusBadge status={run.status} />}
        actions={
          rejected || failed || run.status === "COMPLETED_WITH_ERRORS" ? (
            <Button variant="secondary" size="sm" onClick={onRecalculate}>
              {t("charges.run.recalculate")}
            </Button>
          ) : null
        }
      />
      <div aria-live="polite" aria-atomic="true">
        <Alert variant={rejected || failed || run.status === "COMPLETED_WITH_ERRORS" ? "warning" : "info"}>
          <p>{t(`charges.run.progress.${run.status}`, { evaluated: run.studentsEvaluated, created: run.chargesCreated, failed: run.chargesFailed })}</p>
          {run.failureCode == null ? null : <p className="mt-1 font-medium">{t(failureKeyOf(run.failureCode))}</p>}
          {run.status === "COMPLETED_WITH_ERRORS" ? <p className="mt-1">{t("charges.run.errorsNote")}</p> : null}
        </Alert>
      </div>
      {state.refreshFailed ? <Alert variant="warning">{t("charges.run.pollFailed")}</Alert> : null}
      {lookupFailure === undefined ? null : (
        <Alert variant="warning">
          <p>{t("charges.run.lookupFailed")}</p>
          <Button variant="secondary" size="xs" className="mt-2" onClick={lookupFailure.onRetry}>
            {t("resource.retryOptions")}
          </Button>
        </Alert>
      )}
      <Card padding="sm">
        <dl aria-label={t("charges.run.summary")} className="grid grid-cols-1 gap-x-4 gap-y-2 sm:grid-cols-2 lg:grid-cols-3">
          <Summary term={t("charges.run.plan")}>
            <a
              href={planHref}
              className="underline underline-offset-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-border-focus"
              onClick={(event) => {
                if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
                event.preventDefault();
                onOpenPlan();
              }}
            >
              {planName}
            </a>
          </Summary>
          <Summary term={t("charges.run.period")}>{period}</Summary>
          <Summary term={t("charges.run.preview")}>{run.previewChecked ? t("charges.run.previewChecked") : t("charges.run.previewNotChecked")}</Summary>
          <Summary term={t("charges.run.queuedAt")}>{formatInstant(run.queuedAt)}</Summary>
          <Summary term={t("charges.run.startedAt")}>{run.startedAt == null ? t("charges.run.notYet") : formatInstant(run.startedAt)}</Summary>
          <Summary term={t("charges.run.finishedAt")}>{run.finishedAt == null ? t("charges.run.notYet") : formatInstant(run.finishedAt)}</Summary>
        </dl>
      </Card>
      <div className="grid grid-cols-2 gap-2 lg:grid-cols-4">
        <KeyFigure label={t("charges.run.studentsEvaluated")} value={String(run.studentsEvaluated)} />
        <KeyFigure label={t("charges.run.chargesCreated")} value={String(run.chargesCreated)} />
        <KeyFigure label={t("charges.run.chargesExisting")} value={String(run.chargesExisting)} />
        <KeyFigure label={t("charges.run.chargesFailed")} value={String(run.chargesFailed)} />
      </div>
      <RunItems state={state} onBack={onBack} />
    </section>
  );
}
