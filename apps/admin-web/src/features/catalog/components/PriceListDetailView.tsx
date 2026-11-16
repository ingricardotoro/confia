import { Alert, Badge, Button, EmptyState, ErrorState, MoneyAmount, Skeleton, Table, type TableColumn } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { formatIsoDate } from "../../../shared/format/format-date.ts";
import type { ResourceNotice } from "../../../shared/resource-screen/components/ResourceScreen.tsx";
import type { PriceItem } from "../price-form.ts";
import type { PriceListDetail } from "../price-list-form.ts";

/** The prices of one concept, as the detail shows them: the newest validity first. */
export interface ConceptPricesView {
  readonly paymentConceptId: string;
  /** Absent when the concept is not among the ones loaded: the heading then says it is not available. */
  readonly code?: string | undefined;
  readonly name?: string | undefined;
  readonly items: readonly PriceItem[];
}

export type PriceListDetailState =
  | { readonly kind: "loading" }
  | { readonly kind: "error"; readonly title: string; readonly description: string; readonly onRetry: () => void }
  | {
      readonly kind: "ready";
      readonly list: PriceListDetail;
      /** Already named: the year, the scope's modality or grade (absent for the whole institution). */
      readonly yearName: string;
      readonly referenceName?: string | undefined;
      readonly concepts: readonly ConceptPricesView[];
    };

export interface PriceListDetailViewProps {
  readonly state: PriceListDetailState;
  readonly notice?: ResourceNotice | undefined;
  /** Set when the lists that name the concept, the year, the modality or the grade failed to load: names show as not available. */
  readonly lookupFailure?: { readonly onRetry: () => void } | undefined;
  /** The archive or the reactivation is being saved: the button waits. */
  readonly changingStatus: boolean;
  /** The address of the list of lists, and the navigation to it. */
  readonly backHref: string;
  readonly onBack: () => void;
  readonly onAddPrice: () => void;
  readonly onToggleStatus: () => void;
}

function Summary({ term, children }: { readonly term: string; readonly children: string }) {
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="text-sm text-text-secondary">{term}</dt>
      <dd className="text-base text-text-primary">{children}</dd>
    </div>
  );
}

/**
 * One price list: its data, the archive or reactivation, and a table of validities for each concept it prices. The
 * amounts are shown by the money component of the design system, right aligned and with their currency; nothing here
 * adds, rounds or compares them. A validity with no end is the open one, the others are closed.
 */
export function PriceListDetailView({ state, notice, lookupFailure, changingStatus, backHref, onBack, onAddPrice, onToggleStatus }: PriceListDetailViewProps) {
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
      {t("catalog.priceLists.detail.back")}
    </a>
  );

  if (state.kind === "loading") {
    return (
      <section aria-labelledby="price-list-heading" aria-busy="true" className="flex flex-col gap-4">
        {back}
        <h2 id="price-list-heading" className="sr-only">
          {t("catalog.priceLists.heading")}
        </h2>
        <Skeleton className="h-7 w-72" />
        <Skeleton className="h-16 w-full max-w-xl" />
        <Skeleton className="h-40 w-full" />
      </section>
    );
  }

  if (state.kind === "error") {
    return (
      <section aria-labelledby="price-list-heading" className="flex flex-col gap-4">
        {back}
        <h2 id="price-list-heading" className="sr-only">
          {t("catalog.priceLists.heading")}
        </h2>
        <ErrorState title={state.title} description={state.description} onRetry={state.onRetry} />
      </section>
    );
  }

  const { list, yearName, referenceName, concepts } = state;
  const archived = list.status === "ARCHIVED";
  const unknown = t("resource.unknownReference");

  return (
    <section aria-labelledby="price-list-heading" className="flex flex-col gap-4">
      {back}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 id="price-list-heading" className="text-xl font-semibold text-text-primary">
          {list.name}
        </h2>
        <div className="flex flex-wrap gap-2">
          <Button
            variant="secondary"
            loading={changingStatus}
            aria-label={t(archived ? "catalog.priceLists.detail.reactivateFor" : "catalog.priceLists.detail.archiveFor", { name: list.name })}
            onClick={onToggleStatus}
          >
            {t(archived ? "catalog.priceLists.detail.reactivate" : "catalog.priceLists.detail.archive")}
          </Button>
          <Button disabled={archived} onClick={onAddPrice}>
            {t("catalog.priceLists.detail.addPrice")}
          </Button>
        </div>
      </div>
      <div aria-live="polite" aria-atomic="true">
        {notice === undefined ? null : (
          <Alert variant={notice.tone === "error" ? "destructive" : "info"}>
            <p>{notice.message}</p>
            {notice.traceId === undefined ? null : <p className="mt-1">{t("resource.trace", { traceId: notice.traceId })}</p>}
          </Alert>
        )}
      </div>
      {lookupFailure === undefined ? null : (
        <Alert variant="warning">
          <p>{t("catalog.priceLists.detail.lookupFailed")}</p>
          <Button variant="secondary" size="sm" className="mt-2" onClick={lookupFailure.onRetry}>
            {t("resource.retryOptions")}
          </Button>
        </Alert>
      )}
      {archived ? (
        <Alert variant="warning">
          <p>{t("catalog.priceLists.detail.archivedNote")}</p>
        </Alert>
      ) : null}
      <dl aria-label={t("catalog.priceLists.detail.summary")} className="grid max-w-3xl grid-cols-1 gap-3 sm:grid-cols-2">
        <Summary term={t("catalog.priceLists.columns.year")}>{yearName}</Summary>
        <Summary term={t("catalog.priceLists.columns.scope")}>{t(`catalog.priceLists.scopes.${list.scope}`)}</Summary>
        {list.scope === "INSTITUTION" ? null : <Summary term={t("catalog.priceLists.columns.reference")}>{referenceName ?? unknown}</Summary>}
        <div className="flex flex-col gap-0.5">
          <dt className="text-sm text-text-secondary">{t("catalog.priceLists.columns.status")}</dt>
          <dd>
            <Badge variant={archived ? "outline" : "primary"}>{t(`catalog.priceLists.statuses.${list.status}`)}</Badge>
          </dd>
        </div>
      </dl>
      <h3 className="text-lg font-semibold text-text-primary">{t("catalog.priceLists.detail.pricesHeading")}</h3>
      {concepts.length === 0 ? (
        <EmptyState
          title={t("catalog.priceLists.detail.emptyTitle")}
          description={t("catalog.priceLists.detail.emptyDescription")}
          action={{ label: t("catalog.priceLists.detail.emptyAction"), onAction: onAddPrice }}
        />
      ) : (
        concepts.map((concept) => (
          <ConceptTable key={concept.paymentConceptId} concept={concept} onAddPrice={onAddPrice} />
        ))
      )}
    </section>
  );
}

function ConceptTable({ concept, onAddPrice }: { readonly concept: ConceptPricesView; readonly onAddPrice: () => void }) {
  const { t } = useAdminText();
  const label =
    concept.name === undefined
      ? t("resource.unknownReference")
      : concept.code === undefined
        ? concept.name
        : t("catalog.priceLists.detail.conceptHeading", { code: concept.code, name: concept.name });
  const columns: readonly TableColumn<PriceItem>[] = [
    { id: "amount", header: t("catalog.priceLists.detail.columns.amount"), align: "end", cell: (item) => <MoneyAmount value={item.price} /> },
    { id: "currency", header: t("catalog.priceLists.detail.columns.currency"), cell: (item) => item.price.currency },
    { id: "validFrom", header: t("catalog.priceLists.detail.columns.validFrom"), cell: (item) => formatIsoDate(item.validFrom) },
    {
      id: "validTo",
      header: t("catalog.priceLists.detail.columns.validTo"),
      cell: (item) => (item.validTo === null || item.validTo === undefined ? t("catalog.priceLists.detail.openEnded") : formatIsoDate(item.validTo)),
    },
    {
      id: "state",
      header: t("catalog.priceLists.detail.columns.state"),
      cell: (item) => {
        const open = item.validTo === null || item.validTo === undefined;
        return <Badge variant={open ? "primary" : "outline"}>{t(open ? "catalog.priceLists.detail.validityOpen" : "catalog.priceLists.detail.validityClosed")}</Badge>;
      },
    },
  ];

  return (
    <div className="flex flex-col gap-2">
      <h4 className="text-base font-semibold text-text-primary">{label}</h4>
      <Table
        caption={t("catalog.priceLists.detail.conceptCaption", { name: concept.name ?? t("resource.unknownReference") })}
        columns={columns}
        rows={concept.items}
        getRowId={(item) => item.id}
        empty={{ title: t("catalog.priceLists.detail.emptyTitle"), action: { label: t("catalog.priceLists.detail.emptyAction"), onAction: onAddPrice } }}
      />
    </div>
  );
}
