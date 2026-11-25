import { Alert, Breadcrumb, Button, ErrorState, PageHeader, Skeleton } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { formatInstant, formatIsoDate } from "../../../shared/format/format-date.ts";
import { BackLink } from "../../../shared/layout/components/BackLink.tsx";
import type { ResourceNotice } from "../../../shared/resource-screen/components/ResourceScreen.tsx";
import { scholarshipLabelOf, type Award } from "../scholarships-types.ts";
import { AwardStatusBadge } from "./AwardStatusBadge.tsx";

export type AwardDetailState =
  | { readonly kind: "loading" }
  | { readonly kind: "error"; readonly title: string; readonly description: string; readonly onRetry: () => void }
  /** The answer was no and will stay no: an award that is not in this institution (404) or a missing permission (403). */
  | { readonly kind: "blocked"; readonly title: string; readonly description: string }
  | { readonly kind: "ready"; readonly award: Award; readonly academicYearName: string | undefined };

export interface AwardDetailViewProps {
  readonly state: AwardDetailState;
  readonly backHref: string;
  readonly onBack: () => void;
  /** The result of the last action of the person (an approval, a rejection or a revocation), already translated. */
  readonly notice?: ResourceNotice | undefined;
  /** Present when the award is pending and the person may decide it: the actions that open the decision dialogs. The server decides who may decide. */
  readonly onApprove?: (() => void) | undefined;
  readonly onReject?: (() => void) | undefined;
  /** Present when the award is approved and the person may revoke it. */
  readonly onRevoke?: (() => void) | undefined;
  readonly onViewStudentAwards: (studentId: string) => void;
}

/**
 * One award with its decision. A pending award offers Approve and Reject, and says it changes nothing yet; an approved one offers Revoke, and says that a
 * revocation only affects the charges to come. The interface cannot know who requested the award, so the actions are offered to whoever has the
 * permission and the server answers (it refuses the person who requested it). Dates are the server own, shown as they arrived.
 */
export function AwardDetailView({ state, backHref, onBack, notice, onApprove, onReject, onRevoke, onViewStudentAwards }: AwardDetailViewProps) {
  const { t } = useAdminText();
  const award = state.kind === "ready" ? state.award : undefined;
  const title = award === undefined ? t("scholarships.awards.heading") : t("scholarships.detail.heading", { student: award.studentCode ?? t("scholarships.detail.noValue") });
  const decision = award?.revocation ?? award?.decision;

  return (
    <section aria-labelledby="award-heading" className="flex flex-col gap-3">
      <PageHeader
        title={title}
        headingLevel={2}
        headingId="award-heading"
        {...(award === undefined ? {} : { status: <AwardStatusBadge status={award.status} size="md" /> })}
        breadcrumb={
          award === undefined ? (
            <BackLink href={backHref} label={t("scholarships.detail.back")} onBack={onBack} />
          ) : (
            <Breadcrumb
              label={t("shell.breadcrumbLabel")}
              items={[
                { id: "awards", label: t("scholarships.awards.heading"), href: backHref, onNavigate: onBack },
                { id: "award", label: title },
              ]}
            />
          )
        }
        {...(award === undefined
          ? {}
          : {
              actions: (
                <div className="flex flex-wrap items-center gap-2">
                  <Button
                    variant="secondary"
                    size="sm"
                    onClick={() => {
                      onViewStudentAwards(award.studentId);
                    }}
                  >
                    {t("scholarships.detail.viewStudentAwards")}
                  </Button>
                  {onReject === undefined ? null : (
                    <Button variant="destructive-soft" size="sm" onClick={onReject}>
                      {t("scholarships.detail.reject")}
                    </Button>
                  )}
                  {onApprove === undefined ? null : (
                    <Button variant="success" size="sm" onClick={onApprove}>
                      {t("scholarships.detail.approve")}
                    </Button>
                  )}
                  {onRevoke === undefined ? null : (
                    <Button variant="destructive-soft" size="sm" onClick={onRevoke}>
                      {t("scholarships.detail.revoke")}
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
            {t("scholarships.detail.loading")}
          </p>
          <Skeleton className="h-24 w-full" />
          <Skeleton className="h-24 w-full" />
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
          {state.award.status === "pending" ? (
            <Alert variant="warning">
              <p>{t("scholarships.detail.pendingNote")}</p>
            </Alert>
          ) : null}
          {state.award.status === "revoked" ? (
            <Alert variant="info">
              <p>{t("scholarships.detail.revokedNote")}</p>
            </Alert>
          ) : null}
          <h3 className="text-base font-semibold text-text-primary">{t("scholarships.detail.summaryHeading")}</h3>
          <dl className="grid grid-cols-[auto_1fr] gap-x-6 gap-y-2 rounded-lg border border-border-subtle bg-bg-surface p-4 text-sm">
            <dt className="text-text-secondary">{t("scholarships.detail.fields.student")}</dt>
            <dd>{state.award.studentCode ?? t("scholarships.detail.noValue")}</dd>
            <dt className="text-text-secondary">{t("scholarships.detail.fields.scholarship")}</dt>
            <dd>{scholarshipLabelOf(state.award) ?? t("scholarships.detail.noValue")}</dd>
            <dt className="text-text-secondary">{t("scholarships.detail.fields.academicYear")}</dt>
            <dd>{state.academicYearName ?? t("scholarships.detail.noValue")}</dd>
            <dt className="text-text-secondary">{t("scholarships.detail.fields.validity")}</dt>
            <dd>{t("scholarships.detail.validityValue", { from: formatIsoDate(state.award.validFrom), to: formatIsoDate(state.award.validTo) })}</dd>
            <dt className="text-text-secondary">{t("scholarships.detail.fields.requestedAt")}</dt>
            <dd>{formatInstant(state.award.requestedAt)}</dd>
          </dl>
          {decision === undefined ? null : (
            <>
              <h3 className="text-base font-semibold text-text-primary">{t("scholarships.detail.decisionHeading")}</h3>
              <dl className="grid grid-cols-[auto_1fr] gap-x-6 gap-y-2 rounded-lg border border-border-subtle bg-bg-surface p-4 text-sm">
                <dt className="text-text-secondary">{t("scholarships.detail.fields.decision")}</dt>
                <dd>{t(`scholarships.decisionKinds.${decision.kind}`)}</dd>
                <dt className="text-text-secondary">{t("scholarships.detail.fields.decidedAt")}</dt>
                <dd>{formatInstant(decision.decidedAt)}</dd>
                {decision.reason === undefined ? null : (
                  <>
                    <dt className="text-text-secondary">{t("scholarships.detail.fields.reason")}</dt>
                    <dd className="whitespace-pre-wrap">{decision.reason}</dd>
                  </>
                )}
              </dl>
            </>
          )}
        </>
      )}
    </section>
  );
}
