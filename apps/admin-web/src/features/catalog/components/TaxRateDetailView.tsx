import {
  Alert,
  Badge,
  Breadcrumb,
  Button,
  Card,
  EmptyState,
  ErrorState,
  PageHeader,
  Skeleton,
  Table,
  type TableColumn,
} from "@confia/ui";
import type { ReactNode } from "react";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { formatIsoDate } from "../../../shared/format/format-date.ts";
import { formatRatePercent } from "../../../shared/format/format-rate.ts";
import type { ResourceNotice } from "../../../shared/resource-screen/components/ResourceScreen.tsx";
import type { TaxRateDetail, TaxRatePeriod } from "../tax-rate-form.ts";
import { BackLink } from "../../../shared/layout/components/BackLink.tsx";

export type TaxRateDetailState =
  | { readonly kind: "loading" }
  | {
      readonly kind: "error";
      readonly title: string;
      readonly description: string;
      readonly onRetry: () => void;
    }
  | {
      readonly kind: "ready";
      readonly rate: TaxRateDetail;
      /** The validities from the newest start to the oldest, as the server sent them. */
      readonly periods: readonly TaxRatePeriod[];
    };

export interface TaxRateDetailViewProps {
  readonly state: TaxRateDetailState;
  readonly notice?: ResourceNotice | undefined;
  /** The activation or the deactivation is being saved: the button waits. */
  readonly changingStatus: boolean;
  /** The address of the list of rates, and the navigation to it. */
  readonly backHref: string;
  readonly onBack: () => void;
  readonly onAddPeriod: () => void;
  readonly onToggleStatus: () => void;
}

function Summary({
  term,
  children,
}: {
  readonly term: string;
  readonly children: ReactNode;
}) {
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="text-xs font-semibold tracking-caps text-text-secondary uppercase">
        {term}
      </dt>
      <dd className="text-sm font-medium text-text-primary">{children}</dd>
    </div>
  );
}

/**
 * One tax rate: its data, the activation or deactivation, and the table of its validities (the history). A percentage is
 * shown as the server sent it, at four decimals and with the percent sign of the catalog; nothing here adds, rounds or
 * compares them. A validity with no end is the open one, the others are closed.
 */
export function TaxRateDetailView({
  state,
  notice,
  changingStatus,
  backHref,
  onBack,
  onAddPeriod,
  onToggleStatus,
}: TaxRateDetailViewProps) {
  const { t } = useAdminText();

  const back = <BackLink href={backHref} label={t("catalog.taxRates.detail.back")} onBack={onBack} />;

  if (state.kind === "loading") {
    return (
      <section
        aria-labelledby="tax-rate-heading"
        aria-busy="true"
        className="flex flex-col gap-3"
      >
        <PageHeader title={t("catalog.taxRates.heading")} headingLevel={2} headingId="tax-rate-heading" breadcrumb={back} />
        <Skeleton className="h-16 w-full max-w-xl" />
        <Skeleton className="h-40 w-full" />
      </section>
    );
  }

  if (state.kind === "error") {
    return (
      <section
        aria-labelledby="tax-rate-heading"
        className="flex flex-col gap-3"
      >
        <PageHeader title={t("catalog.taxRates.heading")} headingLevel={2} headingId="tax-rate-heading" breadcrumb={back} />
        <ErrorState
          title={state.title}
          description={state.description}
          onRetry={state.onRetry}
        />
      </section>
    );
  }

  const { rate, periods } = state;
  const inactive = !rate.isActive;
  // An inactive rate takes no new validity, so what the empty state offers is to activate it first.
  const emptyAction = inactive
    ? { label: t("catalog.taxRates.detail.activate"), onAction: onToggleStatus }
    : {
        label: t("catalog.taxRates.detail.emptyAction"),
        onAction: onAddPeriod,
      };

  const columns: readonly TableColumn<TaxRatePeriod>[] = [
    {
      id: "rate",
      header: t("catalog.taxRates.detail.columns.rate"),
      align: "end",
      cell: (period) => (
        <span className="tabular-nums">
          {t("catalog.taxRates.percentValue", {
            rate: formatRatePercent(period.ratePercent),
          })}
        </span>
      ),
    },
    {
      id: "validFrom",
      header: t("catalog.taxRates.detail.columns.validFrom"),
      cell: (period) => formatIsoDate(period.validFrom),
    },
    {
      id: "validTo",
      header: t("catalog.taxRates.detail.columns.validTo"),
      cell: (period) =>
        period.validTo === null || period.validTo === undefined
          ? t("catalog.taxRates.detail.openEnded")
          : formatIsoDate(period.validTo),
    },
    {
      id: "state",
      header: t("catalog.taxRates.detail.columns.state"),
      cell: (period) => {
        const open = period.validTo === null || period.validTo === undefined;
        return (
          <Badge
            variant={open ? "primary" : "outline"}
            size="sm"
            {...(open ? { dot: "filled" as const } : {})}
          >
            {t(
              open
                ? "catalog.taxRates.detail.validityOpen"
                : "catalog.taxRates.detail.validityClosed",
            )}
          </Badge>
        );
      },
    },
  ];

  return (
    <section aria-labelledby="tax-rate-heading" className="flex flex-col gap-3">
      <PageHeader
        title={rate.name}
        headingLevel={2}
        headingId="tax-rate-heading"
        breadcrumb={
          <Breadcrumb
            label={t("shell.breadcrumbLabel")}
            items={[
              {
                id: "rates",
                label: t("catalog.taxRates.heading"),
                href: backHref,
                onNavigate: () => {
                  onBack();
                },
              },
              { id: "rate", label: rate.name },
            ]}
          />
        }
        status={
          <Badge
            variant={inactive ? "neutral" : "success"}
            dot={inactive ? "hollow" : "filled"}
          >
            {t(inactive ? "resource.inactive" : "resource.active")}
          </Badge>
        }
        actions={
          <>
            <Button
              variant={inactive ? "success" : "destructive"}
              size="sm"
              loading={changingStatus}
              aria-label={t(
                inactive
                  ? "catalog.taxRates.detail.activateFor"
                  : "catalog.taxRates.detail.deactivateFor",
                { name: rate.name },
              )}
              onClick={onToggleStatus}
            >
              {t(
                inactive
                  ? "catalog.taxRates.detail.activate"
                  : "catalog.taxRates.detail.deactivate",
              )}
            </Button>
            <Button size="sm" disabled={inactive} onClick={onAddPeriod}>
              {t("catalog.taxRates.detail.addPeriod")}
            </Button>
          </>
        }
      />
      <div aria-live="polite" aria-atomic="true" className="empty:-mt-3">
        {notice === undefined ? null : (
          <Alert variant={notice.tone === "error" ? "destructive" : "info"}>
            <p>{notice.message}</p>
            {notice.traceId === undefined ? null : (
              <p className="mt-1">
                {t("resource.trace", { traceId: notice.traceId })}
              </p>
            )}
          </Alert>
        )}
      </div>
      {inactive ? (
        <Alert variant="warning">
          <p>{t("catalog.taxRates.detail.inactiveNote")}</p>
        </Alert>
      ) : null}
      <Card padding="sm">
        <dl
          aria-label={t("catalog.taxRates.detail.summary")}
          className="grid grid-cols-1 gap-x-4 gap-y-2 sm:grid-cols-3"
        >
          <Summary term={t("catalog.taxRates.columns.code")}>
            <span className="font-identifier">{rate.code}</span>
          </Summary>
          <Summary term={t("catalog.taxRates.columns.name")}>
            {rate.name}
          </Summary>
          <Summary term={t("resource.state")}>
            <Badge
              variant={inactive ? "neutral" : "success"}
              dot={inactive ? "hollow" : "filled"}
            >
              {t(inactive ? "resource.inactive" : "resource.active")}
            </Badge>
          </Summary>
        </dl>
      </Card>
      <h3 className="text-base font-semibold text-text-primary">
        {t("catalog.taxRates.detail.periodsHeading")}
      </h3>
      {periods.length === 0 ? (
        <EmptyState
          title={t("catalog.taxRates.detail.emptyTitle")}
          description={t("catalog.taxRates.detail.emptyDescription")}
          action={emptyAction}
        />
      ) : (
        <Table
          caption={t("catalog.taxRates.detail.periodsCaption", {
            name: rate.name,
          })}
          columns={columns}
          captionHidden
          rows={periods}
          getRowId={(period) => period.id}
          empty={{
            title: t("catalog.taxRates.detail.emptyTitle"),
            action: emptyAction,
          }}
        />
      )}
    </section>
  );
}
