import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { differenceKindOf } from "../reconciliation-model.ts";

/**
 * The difference between the active enrollments and the official total, in a sentence: more, fewer or the same. The meaning
 * is in the words, never in a color or a sign alone. Both numbers are whole counts, not money.
 */
export function DifferenceText({ difference }: { readonly difference: number }) {
  const { t } = useAdminText();
  const kind = differenceKindOf(difference);
  return <p className="text-sm text-text-primary">{t(`imports.reconciliation.difference.${kind}`, { count: Math.abs(difference) })}</p>;
}
