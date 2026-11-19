import { Alert, Button, EmptyState, ErrorState, MoneyAmount, PageHeader, Skeleton, Table, type TableColumn } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { formatInstant } from "../../../shared/format/format-date.ts";
import type { IntegrityFinding, IntegrityRunDetail } from "../charges-types.ts";
import { findingKindKeyOf } from "../integrity-model.ts";
import { IntegrityRunStatusBadge } from "./IntegrityRunStatusBadge.tsx";

export type IntegrityRunDetailState =
  | { readonly kind: "loading" }
  | { readonly kind: "error"; readonly title: string; readonly description: string; readonly onRetry: () => void }
  /** The answer was no and will stay no: a run that is not in this institution (404) or a missing permission (403). */
  | { readonly kind: "blocked"; readonly title: string; readonly description: string }
  | { readonly kind: "ready"; readonly detail: IntegrityRunDetail };

export interface IntegrityRunDetailViewProps {
  readonly state: IntegrityRunDetailState;
  readonly onBack: () => void;
}

/** One run of the integrity check: how it went, and each discrepancy it confirmed with what the charges say and what the ledger holds. */
export function IntegrityRunDetailView({ state, onBack }: IntegrityRunDetailViewProps) {
  const { t } = useAdminText();
  const notApplicable = t("charges.integrity.detail.notApplicable");

  const columns: readonly TableColumn<IntegrityFinding>[] = [
    { id: "kind", header: t("charges.integrity.detail.columns.kind"), cell: (row) => t(findingKindKeyOf(row.kind)) },
    { id: "charge", header: t("charges.integrity.detail.columns.charge"), cell: (row) => (row.chargeId === undefined ? notApplicable : <code className="text-xs break-all">{row.chargeId}</code>) },
    {
      id: "transaction",
      header: t("charges.integrity.detail.columns.transaction"),
      cell: (row) => (row.ledgerTransactionId === undefined ? notApplicable : <code className="text-xs break-all">{row.ledgerTransactionId}</code>),
    },
    { id: "expected", header: t("charges.integrity.detail.columns.expected"), align: "end", cell: (row) => (row.expected === undefined ? notApplicable : <MoneyAmount value={row.expected} size="sm" />) },
    { id: "actual", header: t("charges.integrity.detail.columns.actual"), align: "end", cell: (row) => (row.actual === undefined ? notApplicable : <MoneyAmount value={row.actual} size="sm" />) },
  ];

  const title = state.kind === "ready" ? t("charges.integrity.detail.heading", { date: formatInstant(state.detail.run.startedAt) }) : t("charges.integrity.heading");

  return (
    <section aria-labelledby="integrity-run-heading" className="flex flex-col gap-3">
      <PageHeader
        title={title}
        headingLevel={2}
        headingId="integrity-run-heading"
        actions={
          <Button variant="secondary" size="sm" onClick={onBack}>
            {t("charges.integrity.detail.back")}
          </Button>
        }
      />
      {state.kind === "loading" ? (
        <div aria-busy="true" className="flex flex-col gap-3">
          <p role="status" className="sr-only">
            {t("charges.integrity.detail.loading")}
          </p>
          <Skeleton className="h-24 w-full" />
          <Skeleton className="h-40 w-full" />
        </div>
      ) : state.kind === "error" ? (
        <ErrorState title={state.title} description={state.description} onRetry={state.onRetry} />
      ) : state.kind === "blocked" ? (
        <Alert variant="warning">
          <p className="font-medium">{state.title}</p>
          <p className="mt-1">{state.description}</p>
        </Alert>
      ) : (
        <>
          <dl className="grid grid-cols-1 gap-x-6 gap-y-2 rounded-lg border border-border-subtle p-3 text-sm sm:grid-cols-2 lg:grid-cols-5">
            <div>
              <dt className="text-text-secondary">{t("charges.integrity.columns.status")}</dt>
              <dd className="mt-0.5">
                <IntegrityRunStatusBadge status={state.detail.run.status} />
              </dd>
            </div>
            <div>
              <dt className="text-text-secondary">{t("charges.integrity.detail.started")}</dt>
              <dd className="font-medium text-text-primary">{formatInstant(state.detail.run.startedAt)}</dd>
            </div>
            <div>
              <dt className="text-text-secondary">{t("charges.integrity.detail.finished")}</dt>
              <dd className="font-medium text-text-primary">{formatInstant(state.detail.run.finishedAt)}</dd>
            </div>
            <div>
              <dt className="text-text-secondary">{t("charges.integrity.detail.chargesChecked")}</dt>
              <dd className="font-medium text-text-primary tabular-nums">{String(state.detail.run.chargesChecked)}</dd>
            </div>
            <div>
              <dt className="text-text-secondary">{t("charges.integrity.detail.postingsChecked")}</dt>
              <dd className="font-medium text-text-primary tabular-nums">{String(state.detail.run.postingsChecked)}</dd>
            </div>
          </dl>
          <h3 className="text-base font-semibold text-text-primary">{t("charges.integrity.detail.findingsHeading")}</h3>
          {state.detail.findings.length === 0 ? (
            <EmptyState
              title={t("charges.integrity.detail.noFindingsTitle")}
              description={t("charges.integrity.detail.noFindingsDescription")}
              action={{ label: t("charges.integrity.detail.noFindingsAction"), onAction: onBack }}
            />
          ) : (
            <Table
              stickyHeader
              captionHidden
              caption={t("charges.integrity.detail.caption")}
              columns={columns}
              rows={state.detail.findings}
              empty={{ title: t("charges.integrity.detail.noFindingsTitle"), description: t("charges.integrity.detail.noFindingsDescription"), action: { label: t("charges.integrity.detail.noFindingsAction"), onAction: onBack } }}
              getRowId={(row) => `${row.kind}:${row.chargeId ?? ""}:${row.ledgerTransactionId ?? ""}:${(row.expected ?? row.actual)?.currency ?? ""}`}
            />
          )}
        </>
      )}
    </section>
  );
}
