import { Button, Dialog, ErrorState, Skeleton } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { formatIsoDate } from "../../../shared/format/format-date.ts";
import { studentNameOf, type Enrollment } from "../enrollment-model.ts";

export type EnrollmentDetailState =
  | { readonly kind: "loading" }
  | { readonly kind: "failed"; readonly description: string; readonly onRetry: () => void }
  | { readonly kind: "ready"; readonly row: Enrollment };

export interface EnrollmentDetailProps {
  readonly state: EnrollmentDetailState;
  readonly onClose: () => void;
}

function Fact({ term, children }: { readonly term: string; readonly children: string }) {
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="text-sm text-text-secondary">{term}</dt>
      <dd className="text-sm font-medium text-text-primary">{children}</dd>
    </div>
  );
}

/**
 * The detail of an enrollment, read only, in a dialog: who, where, since when and, if it ended, how and why; and the history
 * of section transfers, oldest first, which nothing edits. Loading is a skeleton with the shape of the facts, a failure
 * offers a retry, and an enrollment that never changed section says so.
 */
export function EnrollmentDetail({ state, onClose }: EnrollmentDetailProps) {
  const { t } = useAdminText();
  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
      title={t("enrollments.detail.title")}
      description={t("enrollments.detail.description")}
      footer={
        <Button variant="secondary" onClick={onClose}>
          {t("enrollments.detail.close")}
        </Button>
      }
    >
      <div aria-live="polite" className="flex flex-col gap-4">
        {state.kind === "loading" ? (
          <div aria-busy="true" className="flex flex-col gap-3">
            <p className="sr-only">{t("enrollments.detail.loading")}</p>
            <Skeleton className="h-10 w-full" />
            <Skeleton className="h-10 w-full" />
            <Skeleton className="h-10 w-2/3" />
          </div>
        ) : null}
        {state.kind === "failed" ? <ErrorState title={t("enrollments.detail.loadFailed")} description={state.description} onRetry={state.onRetry} /> : null}
        {state.kind === "ready" ? <Facts row={state.row} /> : null}
      </div>
    </Dialog>
  );
}

function Facts({ row }: { readonly row: Enrollment }) {
  const { t } = useAdminText();
  const transfers = row.transfers ?? [];
  return (
    <>
      <dl className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <Fact term={t("enrollments.detail.student")}>{`${studentNameOf(row)} · ${row.studentCode}`}</Fact>
        <Fact term={t("enrollments.detail.year")}>{row.academicYearName}</Fact>
        <Fact term={t("enrollments.detail.modality")}>{row.modalityName}</Fact>
        <Fact term={t("enrollments.detail.gradeSection")}>{`${row.gradeName} · ${row.sectionName}`}</Fact>
        <Fact term={t("enrollments.columns.enrolledOn")}>{formatIsoDate(row.enrolledOn)}</Fact>
        <Fact term={t("enrollments.columns.status")}>{t(`enrollments.status.${row.status}`)}</Fact>
        {row.withdrawnOn == null ? null : <Fact term={t("enrollments.columns.withdrawnOn")}>{formatIsoDate(row.withdrawnOn)}</Fact>}
        {row.status === "ACTIVE" ? null : (
          <Fact term={t("enrollments.detail.exitReason")}>{row.withdrawalReason == null || row.withdrawalReason === "" ? t("enrollments.detail.noReason") : row.withdrawalReason}</Fact>
        )}
      </dl>
      <section aria-labelledby="enrollment-history-heading" className="flex flex-col gap-2">
        <h3 id="enrollment-history-heading" className="text-base font-semibold text-text-primary">
          {t("enrollments.detail.historyHeading")}
        </h3>
        {transfers.length === 0 ? (
          <p className="text-sm text-text-secondary">{t("enrollments.detail.historyEmpty")}</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <caption className="sr-only">{t("enrollments.detail.historyCaption")}</caption>
              <thead>
                <tr className="border-b border-border-subtle">
                  <th scope="col" className="py-1.5 pr-3 font-medium">
                    {t("enrollments.detail.historyDate")}
                  </th>
                  <th scope="col" className="py-1.5 pr-3 font-medium">
                    {t("enrollments.detail.historyFrom")}
                  </th>
                  <th scope="col" className="py-1.5 pr-3 font-medium">
                    {t("enrollments.detail.historyTo")}
                  </th>
                  <th scope="col" className="py-1.5 font-medium">
                    {t("enrollments.detail.historyReason")}
                  </th>
                </tr>
              </thead>
              <tbody>
                {transfers.map((transfer) => (
                  <tr key={transfer.id} className="border-b border-border-subtle last:border-0">
                    <td className="py-1.5 pr-3">{formatIsoDate(transfer.transferredOn)}</td>
                    <td className="py-1.5 pr-3">{transfer.fromSectionName}</td>
                    <td className="py-1.5 pr-3">{transfer.toSectionName}</td>
                    <td className="py-1.5">{transfer.reason == null || transfer.reason === "" ? t("enrollments.detail.noReason") : transfer.reason}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  );
}
