import { Alert, Breadcrumb, Button, ErrorState, PageHeader, Skeleton } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { formatInstant } from "../../../shared/format/format-date.ts";
import { BackLink } from "../../../shared/layout/components/BackLink.tsx";
import type { ResourceNotice } from "../../../shared/resource-screen/components/ResourceScreen.tsx";
import type { CashSession } from "../cashbox-types.ts";
import { CashSessionStatusBadge } from "./CashSessionStatusBadge.tsx";
import { CashSessionSummary } from "./CashSessionSummary.tsx";
import { MovementsTable } from "./MovementsTable.tsx";

export type CashSessionDetailState =
  | { readonly kind: "loading" }
  | { readonly kind: "error"; readonly title: string; readonly description: string; readonly onRetry: () => void }
  /** The answer was no and will stay no: a session that is not in this institution or not the person's (404) or a missing permission (403). */
  | { readonly kind: "blocked"; readonly title: string; readonly description: string }
  | { readonly kind: "ready"; readonly session: CashSession };

export interface CashSessionDetailViewProps {
  readonly state: CashSessionDetailState;
  readonly backHref: string;
  readonly onBack: () => void;
  /** The result of the last action of the person (an approval), already translated. */
  readonly notice?: ResourceNotice | undefined;
  /** Present when the session awaits an approval: the action that opens the approval dialog. The server decides who may approve. */
  readonly onApprove?: (() => void) | undefined;
  readonly onPrint: () => void;
}

/**
 * One session with its movements, its close and its approval: the base of the closing report. On paper (the print styles) only the report stays: the
 * header of the session, its summary, its movements and the two signature lines, the cashier's and the approver's. The screen's own actions and
 * navigation do not print. Every amount is the server's, shown as it arrived.
 */
export function CashSessionDetailView({ state, backHref, onBack, notice, onApprove, onPrint }: CashSessionDetailViewProps) {
  const { t } = useAdminText();
  const session = state.kind === "ready" ? state.session : undefined;
  const title = session === undefined ? t("cashbox.sessions.heading") : t("cashbox.detail.heading", { date: formatInstant(session.openedAt) });

  return (
    <section aria-labelledby="cash-session-heading" className="flex flex-col gap-3">
      <PageHeader
        title={title}
        headingLevel={2}
        headingId="cash-session-heading"
        {...(session === undefined ? {} : { status: <CashSessionStatusBadge status={session.status} size="md" /> })}
        breadcrumb={
          <div className="print:hidden">
            {session === undefined ? (
              <BackLink href={backHref} label={t("cashbox.detail.back")} onBack={onBack} />
            ) : (
              <Breadcrumb
                label={t("shell.breadcrumbLabel")}
                items={[
                  { id: "sessions", label: t("cashbox.sessions.heading"), href: backHref, onNavigate: onBack },
                  { id: "session", label: title },
                ]}
              />
            )}
          </div>
        }
        {...(session === undefined
          ? {}
          : {
              actions: (
                <div className="flex flex-wrap items-center gap-2 print:hidden">
                  <Button variant="secondary" size="sm" onClick={onPrint}>
                    {t("cashbox.detail.print")}
                  </Button>
                  {onApprove === undefined ? null : (
                    <Button variant="success" size="sm" onClick={onApprove}>
                      {t("cashbox.detail.approve")}
                    </Button>
                  )}
                </div>
              ),
            })}
      />
      <div aria-live="polite" aria-atomic="true" className="empty:hidden print:hidden">
        {notice === undefined ? null : (
          <Alert variant={notice.tone === "error" ? "destructive" : "info"}>
            <p>{notice.message}</p>
            {notice.traceId === undefined ? null : <p className="mt-1">{t("resource.trace", { traceId: notice.traceId })}</p>}
          </Alert>
        )}
      </div>
      {state.kind === "loading" ? (
        <div aria-busy="true" className="flex flex-col gap-3">
          <p role="status" className="sr-only">
            {t("cashbox.detail.loading")}
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
          {state.session.status === "pending_approval" ? (
            <Alert variant="warning" className="print:hidden">
              <p>{t("cashbox.detail.pendingNote")}</p>
            </Alert>
          ) : null}
          <CashSessionSummary session={state.session} showCashier />
          <h3 className="text-base font-semibold text-text-primary">{t("cashbox.movements.heading")}</h3>
          <MovementsTable movements={state.session.movements} emptyAction={{ label: t("cashbox.detail.backToList"), onAction: onBack }} />
          {state.session.close === undefined ? null : (
            // Only on paper: the line where each person signs the report. The names are not on the screen because the contract gives identifiers, not names.
            <div className="mt-10 hidden grid-cols-2 gap-16 print:grid">
              <div className="border-t border-text-primary pt-1 text-sm">{t("cashbox.detail.signatureCashier")}</div>
              <div className="border-t border-text-primary pt-1 text-sm">{t("cashbox.detail.signatureApprover")}</div>
            </div>
          )}
        </>
      )}
    </section>
  );
}
