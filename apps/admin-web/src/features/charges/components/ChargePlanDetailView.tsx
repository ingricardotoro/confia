import { Alert, Breadcrumb, Button, Card, EmptyState, ErrorState, PageHeader, Skeleton, Table, type TableColumn } from "@confia/ui";
import type { ReactNode } from "react";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { formatIsoDate } from "../../../shared/format/format-date.ts";
import type { ResourceNotice } from "../../../shared/resource-screen/components/ResourceScreen.tsx";
import type { ChargePlanDetail } from "../charge-plan-form.ts";
import { ChargePlanStatusBadge } from "./ChargePlanStatusBadge.tsx";
import { BackLink } from "../../../shared/layout/components/BackLink.tsx";

/** One item of the plan as the detail shows it. The code and the name are absent when the concept is not among the ones loaded. */
export interface ChargePlanItemView {
  readonly id: string;
  readonly code?: string | undefined;
  readonly name?: string | undefined;
  readonly periodsCount: number;
  readonly firstPeriodOn: string;
}

export type ChargePlanDetailState =
  | { readonly kind: "loading" }
  | {
      readonly kind: "error";
      readonly title: string;
      readonly description: string;
      readonly onRetry: () => void;
    }
  | {
      readonly kind: "ready";
      readonly plan: ChargePlanDetail;
      /** Already named: the year, and the modality, grade or section the plan covers. */
      readonly yearName: string;
      readonly referenceName: string;
      readonly items: readonly ChargePlanItemView[];
    };

export interface ChargePlanDetailViewProps {
  readonly state: ChargePlanDetailState;
  readonly notice?: ResourceNotice | undefined;
  /** Set when the lists that name the concepts, the year, the modality, the grade or the section failed to load: names show as not available. */
  readonly lookupFailure?: { readonly onRetry: () => void } | undefined;
  /** The address of the list of plans, and the navigation to it. */
  readonly backHref: string;
  readonly onBack: () => void;
  readonly onEdit: () => void;
  readonly onActivate: () => void;
  readonly onArchive: () => void;
  readonly onAddItem: () => void;
  readonly onRemoveItem: (item: ChargePlanItemView) => void;
}

function Summary({ term, children }: { readonly term: string; readonly children: ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="text-xs font-semibold tracking-caps text-text-secondary uppercase">{term}</dt>
      <dd className="text-sm font-medium text-text-primary">{children}</dd>
    </div>
  );
}

/**
 * One plan: its data, its items, and what can be done with it in the state it is in. A draft can be edited, its items
 * added and removed, and it can be activated; an active plan is frozen (only its name changes, and it can be archived); an
 * archived plan is for reading. The view only draws what the server holds: it adds, counts and dates nothing.
 */
export function ChargePlanDetailView({
  state,
  notice,
  lookupFailure,
  backHref,
  onBack,
  onEdit,
  onActivate,
  onArchive,
  onAddItem,
  onRemoveItem,
}: ChargePlanDetailViewProps) {
  const { t } = useAdminText();

  const back = <BackLink href={backHref} label={t("charges.plans.detail.back")} onBack={onBack} />;

  if (state.kind === "loading") {
    return (
      <section aria-labelledby="charge-plan-heading" aria-busy="true" className="flex flex-col gap-3">
        <PageHeader title={t("charges.plans.heading")} headingLevel={2} headingId="charge-plan-heading" breadcrumb={back} />
        <Skeleton className="h-16 w-full max-w-xl" />
        <Skeleton className="h-40 w-full" />
      </section>
    );
  }

  if (state.kind === "error") {
    return (
      <section aria-labelledby="charge-plan-heading" className="flex flex-col gap-3">
        <PageHeader title={t("charges.plans.heading")} headingLevel={2} headingId="charge-plan-heading" breadcrumb={back} />
        <ErrorState title={state.title} description={state.description} onRetry={state.onRetry} />
      </section>
    );
  }

  const { plan, yearName, referenceName, items } = state;
  const draft = plan.status === "DRAFT";
  const active = plan.status === "ACTIVE";
  const archived = plan.status === "ARCHIVED";
  const unknown = t("resource.unknownReference");

  const columns: readonly TableColumn<ChargePlanItemView>[] = [
    {
      id: "concept",
      header: t("charges.plans.detail.columns.concept"),
      cell: (item) =>
        item.name === undefined ? unknown : item.code === undefined ? item.name : t("charges.plans.detail.conceptLabel", { code: item.code, name: item.name }),
    },
    { id: "periods", header: t("charges.plans.detail.columns.periods"), align: "end", cell: (item) => String(item.periodsCount) },
    { id: "firstPeriod", header: t("charges.plans.detail.columns.firstPeriod"), cell: (item) => formatIsoDate(item.firstPeriodOn) },
    ...(draft
      ? [
          {
            id: "actions",
            header: t("charges.plans.detail.columns.actions"),
            cell: (item: ChargePlanItemView) => (
              <Button
                variant="destructive-soft"
                size="xs"
                aria-label={t("charges.plans.detail.removeItemFor", { name: item.name ?? unknown })}
                onClick={() => {
                  onRemoveItem(item);
                }}
              >
                {t("charges.plans.detail.removeItem")}
              </Button>
            ),
          } satisfies TableColumn<ChargePlanItemView>,
        ]
      : []),
  ];

  return (
    <section aria-labelledby="charge-plan-heading" className="flex flex-col gap-3">
      <PageHeader
        title={plan.name}
        headingLevel={2}
        headingId="charge-plan-heading"
        breadcrumb={
          <Breadcrumb
            label={t("shell.breadcrumbLabel")}
            items={[
              {
                id: "plans",
                label: t("charges.plans.heading"),
                href: backHref,
                onNavigate: () => {
                  onBack();
                },
              },
              { id: "plan", label: plan.name },
            ]}
          />
        }
        status={<ChargePlanStatusBadge status={plan.status} />}
        actions={
          <>
            {archived ? null : (
              <Button variant="secondary" size="sm" aria-label={t("charges.plans.detail.editFor", { name: plan.name })} onClick={onEdit}>
                {t("charges.plans.detail.edit")}
              </Button>
            )}
            {draft ? (
              <Button variant="success" size="sm" aria-label={t("charges.plans.detail.activateFor", { name: plan.name })} onClick={onActivate}>
                {t("charges.plans.detail.activate")}
              </Button>
            ) : null}
            {active ? (
              <Button variant="destructive" size="sm" aria-label={t("charges.plans.detail.archiveFor", { name: plan.name })} onClick={onArchive}>
                {t("charges.plans.detail.archive")}
              </Button>
            ) : null}
            {draft ? (
              <Button size="sm" onClick={onAddItem}>
                {t("charges.plans.detail.addItem")}
              </Button>
            ) : null}
          </>
        }
      />
      <div aria-live="polite" aria-atomic="true" className="empty:-mt-3">
        {notice === undefined ? null : (
          <Alert variant={notice.tone === "error" ? "destructive" : "info"}>
            <p>{notice.message}</p>
            {notice.traceId === undefined ? null : <p className="mt-1">{t("resource.trace", { traceId: notice.traceId })}</p>}
          </Alert>
        )}
      </div>
      {lookupFailure === undefined ? null : (
        <Alert variant="warning">
          <p>{t("charges.plans.detail.lookupFailed")}</p>
          <Button variant="secondary" size="xs" className="mt-2" onClick={lookupFailure.onRetry}>
            {t("resource.retryOptions")}
          </Button>
        </Alert>
      )}
      <Alert variant={draft ? "info" : "warning"}>
        <p>{t(draft ? "charges.plans.detail.draftNote" : active ? "charges.plans.detail.activeNote" : "charges.plans.detail.archivedNote")}</p>
      </Alert>
      <Card padding="sm">
        <dl aria-label={t("charges.plans.detail.summary")} className="grid grid-cols-1 gap-x-4 gap-y-2 sm:grid-cols-2 lg:grid-cols-3">
          <Summary term={t("charges.plans.columns.year")}>{yearName}</Summary>
          <Summary term={t("charges.plans.columns.scope")}>{t(`charges.plans.scopes.${plan.targetScope}`)}</Summary>
          <Summary term={t(`charges.plans.fields.${plan.targetScope === "MODALITY" ? "modality" : plan.targetScope === "GRADE" ? "grade" : "section"}`)}>
            {referenceName}
          </Summary>
          <Summary term={t("charges.plans.columns.frequency")}>{t(`charges.plans.frequencies.${plan.frequency}`)}</Summary>
          <Summary term={t("charges.plans.columns.dueDay")}>{t("charges.plans.dueDayValue", { day: plan.dueDayOfMonth })}</Summary>
          <Summary term={t("charges.plans.columns.status")}>
            <ChargePlanStatusBadge status={plan.status} />
          </Summary>
        </dl>
      </Card>
      <h3 className="text-base font-semibold text-text-primary">{t("charges.plans.detail.itemsHeading")}</h3>
      {items.length === 0 ? (
        <EmptyState
          title={t("charges.plans.detail.emptyTitle")}
          description={t(draft ? "charges.plans.detail.emptyDescription" : "charges.plans.detail.emptyArchived")}
          // A draft can be given its first item; a plan that is no longer a draft only offers the way back.
          action={draft ? { label: t("charges.plans.detail.emptyAction"), onAction: onAddItem } : { label: t("charges.plans.detail.back"), onAction: onBack }}
        />
      ) : (
        <Table
          caption={t("charges.plans.detail.itemsCaption", { name: plan.name })}
          captionHidden
          columns={columns}
          rows={items}
          getRowId={(item) => item.id}
          empty={{ title: t("charges.plans.detail.emptyTitle"), action: { label: t("charges.plans.detail.back"), onAction: onBack } }}
        />
      )}
    </section>
  );
}
