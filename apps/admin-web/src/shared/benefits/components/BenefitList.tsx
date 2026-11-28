import { MoneyAmount } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import type { AppliedBenefit } from "../benefit-types.ts";

export interface BenefitListProps {
  /** The benefits of one charge in the order the server applied them (the scholarship first, then each discount). */
  readonly benefits: readonly AppliedBenefit[];
}

/**
 * The benefits that reduced one charge, one line each: what it is (scholarship or discount), its code and what it took off, in the order the server
 * applied them. A charge with none says so in text. Every amount is the server own, shown as it arrived: nothing here adds, rounds or compares them.
 */
export function BenefitList({ benefits }: BenefitListProps) {
  const { t } = useAdminText();
  if (benefits.length === 0) return <span className="text-text-secondary">{t("benefits.none")}</span>;
  return (
    <ul className="m-0 flex list-none flex-col gap-0.5 p-0">
      {benefits.map((benefit, index) => (
        <li key={`${benefit.source}-${benefit.code}-${String(index)}`} className="flex flex-wrap items-baseline justify-between gap-x-3">
          <span>{t("benefits.line", { source: t(`benefits.sources.${benefit.source}`), code: benefit.code })}</span>
          <MoneyAmount value={benefit.amount} size="sm" />
        </li>
      ))}
    </ul>
  );
}
