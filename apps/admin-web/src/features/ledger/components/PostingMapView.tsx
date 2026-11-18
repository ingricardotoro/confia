import { Alert, Badge, Button, Card, EmptyState, ErrorState, PageHeader, Skeleton } from "@confia/ui";
import type { SyntheticEvent } from "react";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { OptionField, type Option } from "../../../shared/resource-screen/components/fields.tsx";
import type { ResourceNotice } from "../../../shared/resource-screen/components/ResourceScreen.tsx";
import type { RoleId } from "../posting-map-model.ts";

/** One row of the map as the form shows it: the role, the account chosen now and the accounts it may take. */
export interface PostingMapRow {
  readonly id: RoleId;
  readonly group: "core" | "revenue";
  /** The id of the chosen account, or an empty text when none is. It may be a change that is not saved yet. */
  readonly value: string;
  /** The accounts the row takes (active, of the type its role needs), already translated, plus the one it holds if it is not among them. */
  readonly options: readonly Option[];
  /** The server holds an account for this row. */
  readonly configured: boolean;
}

export type PostingMapState =
  | { readonly kind: "loading" }
  | { readonly kind: "error"; readonly description: string; readonly onRetry: () => void }
  | { readonly kind: "ready" };

export interface PostingMapViewProps {
  readonly state: PostingMapState;
  readonly rows: readonly PostingMapRow[];
  /** The roles that are not set and keep the accrual from running. */
  readonly missing: readonly RoleId[];
  /** The chart has no active account at all, so there is nothing to choose from. */
  readonly noAccounts: boolean;
  /** The person cannot change the map: the form is shown for reading only, with a note that says why. */
  readonly readOnly: boolean;
  readonly notice?: ResourceNotice | undefined;
  /** Set when the lists of accounts failed to load. */
  readonly lookupFailure?: { readonly onRetry: () => void } | undefined;
  readonly pendingCount: number;
  readonly submitting: boolean;
  readonly onChange: (id: RoleId, value: string) => void;
  readonly onSave: () => void;
  readonly onReset: () => void;
  readonly onGoToAccounts: () => void;
}

/** The key of the help of a row: each category of revenue shares one. */
const helpKeyOf = (id: RoleId) => (id === "RECEIVABLE" || id === "TAX_PAYABLE" || id === "REVENUE_DEFAULT" ? (`ledger.map.help.${id}` as const) : ("ledger.map.help.REVENUE" as const));

function MapSkeleton() {
  return (
    <div className="flex flex-col gap-3" data-testid="posting-map-skeleton">
      {[2, 7].map((rows) => (
        <div key={rows} className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-bg-surface p-4">
          <Skeleton className="h-5 w-48" />
          {Array.from({ length: rows }, (_, index) => (
            <div key={index} className="flex flex-col gap-1.5">
              <Skeleton className="h-4 w-56" />
              <Skeleton className="h-9 w-full max-w-md" />
            </div>
          ))}
        </div>
      ))}
    </div>
  );
}

/**
 * The map of posting accounts as a form: one select for each role of the map (the receivable, the tax payable and the revenue
 * by category), each with the accounts it may take and a status that is told in text. It says what is missing and what that
 * blocks, and it saves only what the person changed. Nothing here fetches, sends or decides what the server allows.
 */
export function PostingMapView({
  state,
  rows,
  missing,
  noAccounts,
  readOnly,
  notice,
  lookupFailure,
  pendingCount,
  submitting,
  onChange,
  onSave,
  onReset,
  onGoToAccounts,
}: PostingMapViewProps) {
  const { t } = useAdminText();
  const groupRows = (group: PostingMapRow["group"]) => rows.filter((row) => row.group === group);

  function field(row: PostingMapRow) {
    const empty = row.options.length === 0;
    return (
      <div key={row.id} className="grid grid-cols-1 items-end gap-x-3 gap-y-1 sm:grid-cols-[minmax(0,1fr)_auto]">
        <OptionField
          label={t(`ledger.map.roles.${row.id}`)}
          controlId={`posting-map-${row.id}`}
          value={row.value}
          options={row.options}
          placeholder={t("ledger.map.choose")}
          description={empty ? `${t(helpKeyOf(row.id))} ${t("ledger.map.noOptions")}` : t(helpKeyOf(row.id))}
          disabled={readOnly || submitting}
          onChange={(value) => {
            onChange(row.id, value);
          }}
        />
        <div className="pb-6">
          <Badge variant={row.configured ? "success" : "neutral"} dot={row.configured ? "filled" : "hollow"}>
            {t(row.configured ? "ledger.map.status.set" : "ledger.map.status.notSet")}
          </Badge>
        </div>
      </div>
    );
  }

  function body() {
    if (state.kind === "loading") {
      return (
        <>
          <p role="status" className="sr-only">
            {t("ledger.map.loading")}
          </p>
          <MapSkeleton />
        </>
      );
    }
    if (state.kind === "error") {
      return <ErrorState title={t("ledger.map.loadFailed")} description={state.description} onRetry={state.onRetry} />;
    }
    if (noAccounts) {
      return (
        <EmptyState
          title={t("ledger.map.noAccountsTitle")}
          description={t("ledger.map.noAccountsDescription")}
          action={{ label: t("ledger.map.noAccountsAction"), onAction: onGoToAccounts }}
        />
      );
    }
    return (
      <form
        noValidate
        aria-label={t("ledger.map.caption")}
        className="flex flex-col gap-3"
        onSubmit={(event: SyntheticEvent) => {
          event.preventDefault();
          if (!readOnly && !submitting && pendingCount > 0) onSave();
        }}
      >
        {readOnly ? (
          <Alert variant="info" title={t("ledger.map.readOnlyTitle")}>
            <p>{t("ledger.map.readOnlyBody")}</p>
          </Alert>
        ) : null}
        {missing.length > 0 ? (
          <Alert variant="warning" title={t("ledger.map.incompleteTitle")}>
            <div className="flex flex-col gap-1.5">
              <p>{t("ledger.map.incompleteBody")}</p>
              <p>{t("ledger.map.missingLead")}</p>
              <ul className="list-disc pl-5">
                {missing.map((id) => (
                  <li key={id}>{t(`ledger.map.roles.${id}`)}</li>
                ))}
              </ul>
            </div>
          </Alert>
        ) : (
          <p className="text-sm text-text-secondary">{t("ledger.map.completeNote")}</p>
        )}
        <Card title={t("ledger.map.groups.core")} description={t("ledger.map.groups.coreDescription")}>
          <div className="flex flex-col gap-3">{groupRows("core").map(field)}</div>
        </Card>
        <Card title={t("ledger.map.groups.revenue")} description={t("ledger.map.groups.revenueDescription")}>
          <div className="flex flex-col gap-3">{groupRows("revenue").map(field)}</div>
        </Card>
        {readOnly ? null : (
          <div className="flex flex-wrap items-center gap-3">
            <Button type="submit" size="sm" loading={submitting} disabled={pendingCount === 0} aria-describedby="posting-map-pending">
              {t("ledger.map.save")}
            </Button>
            <Button variant="secondary" size="sm" disabled={pendingCount === 0 || submitting} onClick={onReset}>
              {t("ledger.map.reset")}
            </Button>
            <p id="posting-map-pending" className="text-sm text-text-secondary">
              {pendingCount === 0 ? t("ledger.map.nothingToSave") : t("ledger.map.pending", { count: pendingCount })}
            </p>
            <p role="status" className="sr-only">
              {submitting ? t("ledger.map.saving") : ""}
            </p>
          </div>
        )}
      </form>
    );
  }

  return (
    <section aria-labelledby="posting-map-heading" className="flex flex-col gap-3">
      <PageHeader title={t("ledger.map.heading")} headingLevel={2} headingId="posting-map-heading" description={t("ledger.map.description")} />
      {lookupFailure === undefined ? null : (
        <Alert variant="warning">
          <p>{t("resource.optionsFailed")}</p>
          <Button variant="secondary" size="xs" className="mt-2" onClick={lookupFailure.onRetry}>
            {t("resource.retryOptions")}
          </Button>
        </Alert>
      )}
      <div aria-live="polite" aria-atomic="true" className="empty:-mt-3">
        {notice === undefined ? null : (
          <Alert variant={notice.tone === "error" ? "destructive" : "info"}>
            <p>{notice.message}</p>
            {notice.traceId === undefined ? null : <p className="mt-1">{t("resource.trace", { traceId: notice.traceId })}</p>}
          </Alert>
        )}
      </div>
      {body()}
    </section>
  );
}
