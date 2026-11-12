import { Alert, Button, Dialog, ErrorState, Skeleton } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { formatIsoDate } from "../../../shared/format/format-date.ts";
import { formatPercent } from "../../../shared/format/format-percent.ts";
import { nameOf, type Guardian } from "../guardian-form.ts";

export type GuardianDetailState =
  | { readonly kind: "loading" }
  | { readonly kind: "failed"; readonly description: string; readonly onRetry: () => void }
  | { readonly kind: "ready"; readonly row: Guardian };

export interface GuardianDetailProps {
  readonly state: GuardianDetailState;
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
 * The detail of a guardian, read only, in a dialog: who, how to reach them and the students they are linked to now. It shows
 * whether a document and an RTN are registered, never their digits. Loading is a skeleton with the shape of the facts, a
 * failure offers a retry, and a guardian with no students says so.
 */
export function GuardianDetail({ state, onClose }: GuardianDetailProps) {
  const { t } = useAdminText();
  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
      title={t("guardians.detail.title")}
      description={t("guardians.detail.description")}
      footer={
        <Button variant="secondary" onClick={onClose}>
          {t("guardians.detail.close")}
        </Button>
      }
    >
      <div aria-live="polite" className="flex flex-col gap-4">
        {state.kind === "loading" ? (
          <div aria-busy="true" className="flex flex-col gap-3">
            <p className="sr-only">{t("guardians.detail.loading")}</p>
            <Skeleton className="h-10 w-full" />
            <Skeleton className="h-10 w-full" />
            <Skeleton className="h-10 w-2/3" />
          </div>
        ) : null}
        {state.kind === "failed" ? <ErrorState title={t("guardians.detail.loadFailed")} description={state.description} onRetry={state.onRetry} /> : null}
        {state.kind === "ready" ? <Facts row={state.row} /> : null}
      </div>
    </Dialog>
  );
}

function Facts({ row }: { readonly row: Guardian }) {
  const { t } = useAdminText();
  const students = row.students ?? [];
  const optional = (value: string | null | undefined): string => (value == null || value === "" ? t("guardians.detail.notProvided") : value);
  const yesNo = (value: boolean): string => t(value ? "guardians.detail.yes" : "guardians.detail.no");
  return (
    <>
      {row.status === "INACTIVE" && students.length > 0 ? <Alert variant="warning">{t("guardians.detail.inactiveWithLinks")}</Alert> : null}
      <dl className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <Fact term={t("guardians.fields.code")}>{row.guardianCode}</Fact>
        <Fact term={t("guardians.detail.name")}>{nameOf(row)}</Fact>
        <Fact term={t("guardians.fields.nationalId")}>{t(row.hasNationalId ? "guardians.registered" : "guardians.noneText")}</Fact>
        <Fact term={t("guardians.fields.rtn")}>{t(row.hasRtn ? "guardians.registered" : "guardians.noneText")}</Fact>
        <Fact term={t("guardians.detail.email")}>{optional(row.email)}</Fact>
        <Fact term={t("guardians.detail.phone")}>{optional(row.phone)}</Fact>
        <Fact term={t("guardians.detail.address")}>{optional(row.address)}</Fact>
        <Fact term={t("guardians.fields.preferredChannel")}>{t(`guardians.channel.${row.preferredChannel}`)}</Fact>
        <Fact term={t("guardians.fields.status")}>{t(row.status === "ACTIVE" ? "organization.common.active" : "organization.common.inactive")}</Fact>
      </dl>
      <section aria-labelledby="guardian-students-heading" className="flex flex-col gap-2">
        <h3 id="guardian-students-heading" className="text-base font-semibold text-text-primary">
          {t("guardians.detail.studentsHeading")}
        </h3>
        {students.length === 0 ? (
          <p className="text-sm text-text-secondary">{t("guardians.detail.studentsEmpty")}</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <caption className="sr-only">{t("guardians.detail.studentsCaption")}</caption>
              <thead>
                <tr className="border-b border-border-subtle">
                  <th scope="col" className="py-1.5 pr-3 font-medium">
                    {t("guardians.detail.student")}
                  </th>
                  <th scope="col" className="py-1.5 pr-3 font-medium">
                    {t("guardians.detail.relationship")}
                  </th>
                  <th scope="col" className="py-1.5 pr-3 text-right font-medium">
                    {t("guardians.detail.percent")}
                  </th>
                  <th scope="col" className="py-1.5 pr-3 font-medium">
                    {t("guardians.detail.primaryPayer")}
                  </th>
                  <th scope="col" className="py-1.5 pr-3 font-medium">
                    {t("guardians.detail.billingRecipient")}
                  </th>
                  <th scope="col" className="py-1.5 font-medium">
                    {t("guardians.detail.since")}
                  </th>
                </tr>
              </thead>
              <tbody>
                {students.map((link) => (
                  <tr key={link.linkId} className="border-b border-border-subtle last:border-0">
                    <td className="py-1.5 pr-3">{`${link.studentFirstName} ${link.studentLastName} · ${link.studentCode}`}</td>
                    <td className="py-1.5 pr-3">{t(`guardians.relationship.${link.relationship}`)}</td>
                    <td className="py-1.5 pr-3 text-right tabular-nums">{t("guardians.percentValue", { percent: formatPercent(link.financialResponsibilityPercent) })}</td>
                    <td className="py-1.5 pr-3">{yesNo(link.isPrimaryPayer)}</td>
                    <td className="py-1.5 pr-3">{yesNo(link.isBillingRecipient)}</td>
                    <td className="py-1.5">{formatIsoDate(link.validFrom)}</td>
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
