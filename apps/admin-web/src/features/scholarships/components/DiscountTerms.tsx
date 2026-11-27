import { MoneyAmount } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { formatIsoDate } from "../../../shared/format/format-date.ts";
import type { Discount } from "../discounts-types.ts";

/** The terms of a discount as the server states them: the ones in force are the fields of the discount, the proposed ones are its `pendingChange`. */
export type DiscountTermsOf = Pick<Discount, "name" | "benefitType" | "percent" | "fixedAmount" | "validFrom" | "validTo" | "stackable" | "concepts"> & {
  /** Only a proposal says it: approving it also turns the discount on. */
  readonly reactivate?: boolean | undefined;
};

/** The benefit as the server holds it: a percentage as points, a fixed amount as money. Nothing is added, rounded or compared. */
export function BenefitValue({ terms }: { readonly terms: Pick<DiscountTermsOf, "benefitType" | "percent" | "fixedAmount"> }) {
  const { t } = useAdminText();
  return terms.benefitType === "FIXED_AMOUNT" && terms.fixedAmount !== undefined ? (
    <MoneyAmount value={terms.fixedAmount} size="sm" emphasis="strong" />
  ) : (
    <span className="tabular-nums">{t("scholarships.list.percentValue", { percent: terms.percent ?? "" })}</span>
  );
}

/** The terms of a discount as a list of facts: name, benefit, validity, whether it stacks, the concepts, and (for a proposal) that it turns the discount on. */
export function DiscountTerms({ terms }: { readonly terms: DiscountTermsOf }) {
  const { t } = useAdminText();
  return (
    <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
      <dt className="text-text-secondary">{t("scholarships.discounts.terms.name")}</dt>
      <dd>{terms.name}</dd>
      <dt className="text-text-secondary">{t("scholarships.discounts.terms.benefit")}</dt>
      <dd>
        <BenefitValue terms={terms} />
      </dd>
      <dt className="text-text-secondary">{t("scholarships.discounts.terms.validity")}</dt>
      <dd className="tabular-nums">{t("scholarships.discounts.validityValue", { from: formatIsoDate(terms.validFrom), to: formatIsoDate(terms.validTo) })}</dd>
      <dt className="text-text-secondary">{t("scholarships.discounts.terms.stackable")}</dt>
      <dd>{t(terms.stackable ? "scholarships.discounts.stackableYes" : "scholarships.discounts.stackableNo")}</dd>
      <dt className="text-text-secondary">{t("scholarships.discounts.terms.concepts")}</dt>
      <dd>{terms.concepts.map((concept) => concept.name).join(", ")}</dd>
      {terms.reactivate === true ? (
        <>
          <dt className="text-text-secondary">{t("scholarships.discounts.terms.reactivate")}</dt>
          <dd>{t("scholarships.discounts.proposal.reactivate")}</dd>
        </>
      ) : null}
    </dl>
  );
}
