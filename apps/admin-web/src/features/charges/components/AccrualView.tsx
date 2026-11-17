import { Alert, Button, Card, EmptyState, ErrorState, KeyFigure, MoneyAmount, PageHeader, Pagination, Skeleton, Table, type TableColumn } from "@confia/ui";
import { useState, type SyntheticEvent } from "react";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import type { AccrualPreview } from "../../../shared/api/charges-operations.ts";
import { OptionField, type Option } from "../../../shared/resource-screen/components/fields.tsx";
import { ALL_ROWS, blockerKeyOf, ERROR_ROWS, filterPreviewRows, itemErrorHelpKeyOf, itemErrorKeyOf, pageOfRows, PREVIEW_PAGE_SIZE } from "../accrual-model.ts";
import { AccrualOutcomeBadge } from "./AccrualOutcomeBadge.tsx";

/** Names a student or a concept by id, as the lookups loaded them: a name that is not there reads as not available. */
export interface AccrualNames {
  readonly student: (id: string) => string;
  readonly concept: (id: string) => string;
}

/** A select of the selection form and what it is doing. */
export interface AccrualChoice {
  readonly value: string;
  readonly options: readonly Option[];
  readonly onChange: (value: string) => void;
}

export type AccrualPeriodsState =
  | { readonly kind: "idle" }
  | { readonly kind: "loading" }
  | { readonly kind: "empty" }
  | { readonly kind: "error"; readonly description: string; readonly onRetry: () => void }
  | { readonly kind: "ready" };

export type AccrualPlansState =
  | { readonly kind: "loading" }
  | { readonly kind: "error"; readonly description: string; readonly onRetry: () => void }
  | { readonly kind: "empty" }
  | { readonly kind: "ready" };

export type AccrualResultState =
  | { readonly kind: "idle" }
  | { readonly kind: "loading" }
  | { readonly kind: "error"; readonly description: string; readonly onRetry: () => void }
  | {
      readonly kind: "ready";
      readonly preview: AccrualPreview;
      readonly planName: string;
      readonly periodLabel: string;
      /** The address of the plan, so a blocker that is about the plan can lead to it. */
      readonly planHref: string;
      readonly names: AccrualNames;
    };

export interface AccrualViewProps {
  readonly plans: AccrualChoice & { readonly state: AccrualPlansState };
  readonly periods: AccrualChoice & { readonly state: AccrualPeriodsState };
  /** A plan and a period are chosen, so the preview can be asked for. */
  readonly canPreview: boolean;
  readonly previewing: boolean;
  readonly onPreview: () => void;
  readonly result: AccrualResultState;
  /** Set when the lists of students or concepts failed to load: names show as not available. */
  readonly lookupFailure?: { readonly onRetry: () => void } | undefined;
  readonly onConfirm: () => void;
  readonly onViewRuns: () => void;
  readonly onOpenPlan: () => void;
  readonly onGoToPlans: () => void;
}

const BLOCKERS_ABOUT_THE_PLAN: readonly string[] = ["plan-not-active", "plan-target-outside-year"];

function link(href: string, onNavigate: () => void, label: string) {
  return (
    <a
      href={href}
      className="w-fit text-sm font-medium text-text-primary underline underline-offset-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-border-focus"
      onClick={(event) => {
        if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
        event.preventDefault();
        onNavigate();
      }}
    >
      {label}
    </a>
  );
}

/** The rows of a preview, one student and one concept each. The server sent them all (500 at most): the view only pages and filters what it was given. */
function PreviewItems({ preview, names, onRecalculate }: { readonly preview: AccrualPreview; readonly names: AccrualNames; readonly onRecalculate: () => void }) {
  const { t } = useAdminText();
  const [filter, setFilter] = useState(ALL_ROWS);
  const [page, setPage] = useState(0);
  const kept = filterPreviewRows(preview.items, filter);
  const filterOptions: readonly Option[] = [
    { value: ERROR_ROWS, label: t("charges.accrual.items.filterErrors") },
    ...Object.keys(preview.errorsByCode).map((code) => ({ value: code, label: t(itemErrorKeyOf(code)) })),
  ];
  const noAmount = t("charges.accrual.items.noAmount");

  const columns: readonly TableColumn<AccrualPreview["items"][number]>[] = [
    { id: "student", header: t("charges.accrual.items.student"), cell: (item) => names.student(item.studentId) },
    { id: "concept", header: t("charges.accrual.items.concept"), cell: (item) => names.concept(item.paymentConceptId) },
    { id: "base", header: t("charges.accrual.items.base"), align: "end", cell: (item) => (item.base === undefined ? noAmount : <MoneyAmount value={item.base} size="sm" />) },
    { id: "tax", header: t("charges.accrual.items.tax"), align: "end", cell: (item) => (item.tax === undefined ? noAmount : <MoneyAmount value={item.tax} size="sm" />) },
    { id: "total", header: t("charges.accrual.items.total"), align: "end", cell: (item) => (item.total === undefined ? noAmount : <MoneyAmount value={item.total} size="sm" emphasis="strong" />) },
    { id: "result", header: t("charges.accrual.items.result"), cell: (item) => <AccrualOutcomeBadge mode="preview" outcome={item.outcome} errorCode={item.errorCode} /> },
  ];

  return (
    <section aria-labelledby="accrual-items-heading" className="flex flex-col gap-2">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <h3 id="accrual-items-heading" className="text-base font-semibold text-text-primary">
          {t("charges.accrual.items.heading")}
        </h3>
        <OptionField
          label={t("charges.accrual.items.filter")}
          controlId="accrual-items-filter"
          value={filter}
          options={filterOptions}
          placeholder={t("charges.accrual.items.filterAll")}
          onChange={(next) => {
            setFilter(next);
            setPage(0);
          }}
        />
      </div>
      {preview.itemsTruncated ? <Alert variant="info">{t("charges.accrual.items.truncated")}</Alert> : null}
      <Table
        caption={t("charges.accrual.items.caption")}
        captionHidden
        density="compact"
        columns={columns}
        rows={pageOfRows(kept, page, PREVIEW_PAGE_SIZE)}
        getRowId={(item) => `${item.studentId}:${item.paymentConceptId}`}
        empty={
          preview.items.length === 0
            ? { title: t("charges.accrual.items.emptyTitle"), description: t("charges.accrual.items.emptyDescription"), action: { label: t("charges.run.recalculate"), onAction: onRecalculate } }
            : {
                title: t("charges.accrual.items.emptyFilteredTitle"),
                description: t("charges.accrual.items.emptyFilteredDescription"),
                action: { label: t("charges.accrual.items.emptyFilteredAction"), onAction: () => { setFilter(ALL_ROWS); setPage(0); } },
              }
        }
      />
      <Pagination page={page} size={PREVIEW_PAGE_SIZE} total={kept.length} onPageChange={setPage} label={t("charges.accrual.items.pagination")} />
    </section>
  );
}

function PreviewResult({
  state,
  onConfirm,
  onOpenPlan,
  onRecalculate,
}: {
  readonly state: Extract<AccrualResultState, { kind: "ready" }>;
  readonly onConfirm: () => void;
  readonly onOpenPlan: () => void;
  readonly onRecalculate: () => void;
}) {
  const { t } = useAdminText();
  const { preview, planName, periodLabel, planHref, names } = state;
  const blocked = preview.blockers.length > 0;
  const nothing = preview.toCreate === 0;
  const errorCodes = Object.entries(preview.errorsByCode);

  return (
    <div className="flex flex-col gap-3">
      {blocked ? (
        <Alert variant="warning">
          <div className="flex flex-col gap-1.5">
            <p className="font-semibold">{t("charges.accrual.blockers.heading")}</p>
            <ul className="list-disc pl-5">
              {preview.blockers.map((blocker) => (
                <li key={blocker}>{t(blockerKeyOf(blocker))}</li>
              ))}
            </ul>
            {preview.blockers.some((blocker) => BLOCKERS_ABOUT_THE_PLAN.includes(blocker)) ? link(planHref, onOpenPlan, t("charges.accrual.blockers.openPlan")) : null}
          </div>
        </Alert>
      ) : null}
      <Card
        title={t("charges.accrual.summary.title")}
        description={t("charges.accrual.summary.description", { plan: planName, period: periodLabel })}
        actions={
          <Button variant="success" size="sm" disabled={blocked || nothing} onClick={onConfirm}>
            {t("charges.accrual.confirm")}
          </Button>
        }
      >
        <div className="flex flex-col gap-3">
          {!blocked && nothing ? <p className="text-sm text-text-secondary">{t("charges.accrual.nothingToCreate")}</p> : null}
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-5">
            <KeyFigure label={t("charges.accrual.summary.studentsEvaluated")} value={String(preview.studentsEvaluated)} />
            <KeyFigure label={t("charges.accrual.summary.toCreate")} value={String(preview.toCreate)} />
            <KeyFigure label={t("charges.accrual.summary.alreadyAccrued")} value={String(preview.alreadyAccrued)} />
            <KeyFigure label={t("charges.accrual.summary.alreadyAccruedOtherPlan")} value={String(preview.alreadyAccruedOtherPlan)} />
            <KeyFigure label={t("charges.accrual.summary.errors")} value={String(preview.errors)} />
          </div>
          {preview.totals.length === 0 ? null : (
            <Table
              caption={t("charges.accrual.summary.totalsCaption")}
              density="compact"
              columns={[
                { id: "currency", header: t("charges.accrual.summary.currency"), cell: (total) => total.currency },
                { id: "charges", header: t("charges.accrual.summary.charges"), align: "end", cell: (total) => String(total.charges) },
                { id: "base", header: t("charges.accrual.summary.base"), align: "end", cell: (total) => <MoneyAmount value={total.base} size="sm" /> },
                { id: "tax", header: t("charges.accrual.summary.tax"), align: "end", cell: (total) => <MoneyAmount value={total.tax} size="sm" /> },
                { id: "total", header: t("charges.accrual.summary.total"), align: "end", cell: (total) => <MoneyAmount value={total.total} size="sm" emphasis="strong" /> },
              ]}
              rows={preview.totals}
              getRowId={(total) => total.currency}
              empty={{ title: t("charges.accrual.items.emptyTitle"), action: { label: t("charges.run.recalculate"), onAction: onRecalculate } }}
            />
          )}
          {errorCodes.length === 0 ? null : (
            <section aria-labelledby="accrual-errors-heading" className="flex flex-col gap-1.5">
              <h4 id="accrual-errors-heading" className="text-sm font-semibold text-text-primary">
                {t("charges.accrual.summary.errorsHeading")}
              </h4>
              <p className="text-sm text-text-secondary">{t("charges.accrual.summary.errorsDescription")}</p>
              <ul className="flex flex-col gap-1 text-sm">
                {errorCodes.map(([code, count]) => (
                  <li key={code}>
                    <span className="font-medium text-text-primary">
                      {t("charges.accrual.summary.errorLead", { name: t(itemErrorKeyOf(code)), count: t("charges.accrual.summary.errorCount", { count }) })}
                    </span>{" "}
                    <span className="text-text-secondary">{t(itemErrorHelpKeyOf(code))}</span>
                  </li>
                ))}
              </ul>
            </section>
          )}
        </div>
      </Card>
      <PreviewItems key={preview.previewDigest} preview={preview} names={names} onRecalculate={onRecalculate} />
    </div>
  );
}

/**
 * The generation of charges: choose an active plan and one of its periods, calculate the preview, and confirm. The preview
 * shows what the server would do with nothing written: counts, the totals by currency exactly as the server rounded them,
 * the errors by code and a row for each student and concept. The view adds, rounds and dates nothing: every figure it draws is
 * a string the server sent, drawn through the money component.
 */
export function AccrualView({
  plans,
  periods,
  canPreview,
  previewing,
  onPreview,
  result,
  lookupFailure,
  onConfirm,
  onViewRuns,
  onOpenPlan,
  onGoToPlans,
}: AccrualViewProps) {
  const { t } = useAdminText();

  const header = (
    <PageHeader
      title={t("charges.accrual.heading")}
      headingLevel={2}
      headingId="charge-accrual-heading"
      description={t("charges.accrual.description")}
      actions={
        <Button variant="secondary" size="sm" onClick={onViewRuns}>
          {t("charges.accrual.viewRuns")}
        </Button>
      }
    />
  );

  if (plans.state.kind === "loading") {
    return (
      <section aria-labelledby="charge-accrual-heading" aria-busy="true" className="flex flex-col gap-3">
        {header}
        <Skeleton className="h-24 w-full" />
        <Skeleton className="h-40 w-full" />
      </section>
    );
  }

  if (plans.state.kind === "error") {
    return (
      <section aria-labelledby="charge-accrual-heading" className="flex flex-col gap-3">
        {header}
        <ErrorState title={t("charges.accrual.plansFailed")} description={plans.state.description} onRetry={plans.state.onRetry} />
      </section>
    );
  }

  if (plans.state.kind === "empty") {
    return (
      <section aria-labelledby="charge-accrual-heading" className="flex flex-col gap-3">
        {header}
        <EmptyState
          title={t("charges.accrual.noPlansTitle")}
          description={t("charges.accrual.noPlansDescription")}
          action={{ label: t("charges.accrual.noPlansAction"), onAction: onGoToPlans }}
        />
      </section>
    );
  }

  const periodsReady = periods.state.kind === "ready";

  return (
    <section aria-labelledby="charge-accrual-heading" className="flex flex-col gap-3">
      {header}
      <form
        aria-label={t("charges.accrual.selectionLabel")}
        className="flex flex-wrap items-end gap-x-3 gap-y-2 rounded-lg border border-border-subtle bg-bg-surface px-3 py-2"
        onSubmit={(event: SyntheticEvent) => {
          event.preventDefault();
          if (canPreview && !previewing) onPreview();
        }}
      >
        <OptionField
          label={t("charges.accrual.plan")}
          controlId="accrual-plan"
          value={plans.value}
          options={plans.options}
          placeholder={t("charges.accrual.choosePlan")}
          description={t("charges.accrual.planHelp")}
          onChange={plans.onChange}
        />
        <OptionField
          label={t("charges.accrual.period")}
          controlId="accrual-period"
          value={periods.value}
          options={periods.options}
          placeholder={t("charges.accrual.choosePeriod")}
          description={t("charges.accrual.periodHelp")}
          disabled={!periodsReady}
          onChange={periods.onChange}
        />
        <Button type="submit" size="sm" loading={previewing} disabled={!canPreview}>
          {t("charges.accrual.preview")}
        </Button>
      </form>
      {periods.state.kind === "loading" ? <Skeleton className="h-9 w-full max-w-md" /> : null}
      {periods.state.kind === "error" ? (
        <Alert variant="warning">
          <p>{t("charges.accrual.periodsFailed")}</p>
          <p className="mt-1">{periods.state.description}</p>
          <Button variant="secondary" size="xs" className="mt-2" onClick={periods.state.onRetry}>
            {t("resource.retryOptions")}
          </Button>
        </Alert>
      ) : null}
      {periods.state.kind === "empty" ? (
        <Alert variant="info">
          <p className="font-semibold">{t("charges.accrual.noPeriodsTitle")}</p>
          <p>{t("charges.accrual.noPeriodsDescription")}</p>
        </Alert>
      ) : null}
      {lookupFailure === undefined ? null : (
        <Alert variant="warning">
          <p>{t("charges.run.lookupFailed")}</p>
          <Button variant="secondary" size="xs" className="mt-2" onClick={lookupFailure.onRetry}>
            {t("resource.retryOptions")}
          </Button>
        </Alert>
      )}
      {result.kind === "idle" ? (
        <EmptyState
          title={t("charges.accrual.idleTitle")}
          description={t("charges.accrual.idleDescription")}
          action={{ label: t("charges.accrual.viewRuns"), onAction: onViewRuns }}
        />
      ) : null}
      {result.kind === "loading" ? (
        <div role="status" aria-busy="true" className="flex flex-col gap-2">
          <span className="sr-only">{t("charges.accrual.previewing")}</span>
          <Skeleton className="h-28 w-full" />
          <Skeleton className="h-48 w-full" />
        </div>
      ) : null}
      {result.kind === "error" ? <ErrorState title={t("charges.accrual.previewFailed")} description={result.description} onRetry={result.onRetry} /> : null}
      {result.kind === "ready" ? <PreviewResult state={result} onConfirm={onConfirm} onOpenPlan={onOpenPlan} onRecalculate={onPreview} /> : null}
    </section>
  );
}
