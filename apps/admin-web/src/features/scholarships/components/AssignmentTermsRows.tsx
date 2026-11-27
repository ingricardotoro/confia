import { useAdminText } from "../../../i18n/use-admin-text.ts";
import type { DiscountAssignment } from "../discounts-types.ts";
import { BenefitValue } from "./DiscountTerms.tsx";

/**
 * The terms of the discount an assignment was requested for, as rows of a description list: the benefit (a percentage or a fixed amount) and the version of
 * the discount. The approval is bound to them: the person sees what they are approving, and the server refuses (409 `discount-terms-changed`) when the
 * discount changed since. An assignment from before the terms were kept has none, and the rows say so instead of inventing them.
 */
export function AssignmentTermsRows({ assignment }: { readonly assignment: Pick<DiscountAssignment, "benefitType" | "percent" | "fixedAmount" | "discountVersion"> }) {
  const { t } = useAdminText();
  const { benefitType } = assignment;
  return (
    <>
      <dt className="text-text-secondary">{t("scholarships.assignments.detail.fields.benefit")}</dt>
      <dd>
        {benefitType == null ? (
          t("scholarships.assignments.detail.noValue")
        ) : (
          <BenefitValue terms={{ benefitType, percent: assignment.percent, fixedAmount: assignment.fixedAmount }} />
        )}
      </dd>
      <dt className="text-text-secondary">{t("scholarships.assignments.detail.fields.discountVersion")}</dt>
      <dd className="tabular-nums">{assignment.discountVersion == null ? t("scholarships.assignments.detail.noValue") : String(assignment.discountVersion)}</dd>
    </>
  );
}
