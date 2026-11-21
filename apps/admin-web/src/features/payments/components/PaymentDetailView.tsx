import { Alert, Breadcrumb, Button, ErrorState, PageHeader, Skeleton } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { formatIsoDate } from "../../../shared/format/format-date.ts";
import { BackLink } from "../../../shared/layout/components/BackLink.tsx";
import type { ResourceNotice } from "../../../shared/resource-screen/components/ResourceScreen.tsx";
import type { Payment } from "../payments-types.ts";
import { AllocationsTable } from "./AllocationsTable.tsx";
import { PaymentStatusBadge } from "./PaymentStatusBadge.tsx";
import { PaymentSummary } from "./PaymentSummary.tsx";

export type PaymentDetailState =
  | { readonly kind: "loading" }
  | { readonly kind: "error"; readonly title: string; readonly description: string; readonly onRetry: () => void }
  /** The answer was no and will stay no: a payment that is not in this institution or not the person (404) or a missing permission (403). */
  | { readonly kind: "blocked"; readonly title: string; readonly description: string }
  | { readonly kind: "ready"; readonly payment: Payment };

export interface PaymentDetailViewProps {
  readonly state: PaymentDetailState;
  readonly backHref: string;
  readonly onBack: () => void;
  /** The result of the last action of the person (a confirmation or a rejection), already translated. */
  readonly notice?: ResourceNotice | undefined;
  /** Present when the payment is a declared transfer: the actions that open the decision dialogs. The server decides who may decide. */
  readonly onConfirm?: (() => void) | undefined;
  readonly onReject?: (() => void) | undefined;
  readonly onViewStatement: (studentId: string) => void;
}

/**
 * One payment with its allocations and its decision. A declared transfer offers Confirm and Reject, and says that it has changed nothing yet;
 * the interface cannot know who may decide, so the actions are offered and the server answers (it refuses the person who registered it and
 * anyone without the permission). Every amount is the server own, shown as it arrived.
 */
export function PaymentDetailView({ state, backHref, onBack, notice, onConfirm, onReject, onViewStatement }: PaymentDetailViewProps) {
  const { t } = useAdminText();
  const payment = state.kind === "ready" ? state.payment : undefined;
  const title = payment === undefined ? t("payments.list.heading") : t("payments.detail.heading", { date: formatIsoDate(payment.receivedOn) });
  const rejected = payment?.decision?.kind === "rejected";

  return (
    <section aria-labelledby="payment-heading" className="flex flex-col gap-3">
      <PageHeader
        title={title}
        headingLevel={2}
        headingId="payment-heading"
        {...(payment === undefined ? {} : { status: <PaymentStatusBadge status={payment.status} size="md" /> })}
        breadcrumb={
          payment === undefined ? (
            <BackLink href={backHref} label={t("payments.detail.back")} onBack={onBack} />
          ) : (
            <Breadcrumb
              label={t("shell.breadcrumbLabel")}
              items={[
                { id: "payments", label: t("payments.list.heading"), href: backHref, onNavigate: onBack },
                { id: "payment", label: title },
              ]}
            />
          )
        }
        {...(payment === undefined
          ? {}
          : {
              actions: (
                <div className="flex flex-wrap items-center gap-2">
                  <Button
                    variant="secondary"
                    size="sm"
                    onClick={() => {
                      onViewStatement(payment.studentId);
                    }}
                  >
                    {t("payments.detail.viewStatement")}
                  </Button>
                  {onReject === undefined ? null : (
                    <Button variant="destructive-soft" size="sm" onClick={onReject}>
                      {t("payments.detail.reject")}
                    </Button>
                  )}
                  {onConfirm === undefined ? null : (
                    <Button variant="success" size="sm" onClick={onConfirm}>
                      {t("payments.detail.confirm")}
                    </Button>
                  )}
                </div>
              ),
            })}
      />
      <div aria-live="polite" aria-atomic="true" className="empty:hidden">
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
            {t("payments.detail.loading")}
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
          {state.payment.status === "declared" ? (
            <Alert variant="warning">
              <p>{t("payments.detail.declaredNote")}</p>
            </Alert>
          ) : null}
          {rejected ? (
            <Alert variant="info">
              <p>{t("payments.detail.rejectedNote")}</p>
            </Alert>
          ) : null}
          <h3 className="text-base font-semibold text-text-primary">{t("payments.detail.summaryHeading")}</h3>
          <PaymentSummary payment={state.payment} />
          {state.payment.status === "confirmed" ? (
            <>
              <h3 className="text-base font-semibold text-text-primary">{t("payments.detail.allocationsHeading")}</h3>
              <AllocationsTable
                allocations={state.payment.allocations}
                emptyAction={{
                  label: t("payments.detail.viewStatement"),
                  onAction: () => {
                    onViewStatement(state.payment.studentId);
                  },
                }}
              />
            </>
          ) : null}
        </>
      )}
    </section>
  );
}
