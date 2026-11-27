import { Badge } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import type { DiscountApprovalState } from "../discount-approval.ts";

const WARNING = "border-(--alert-warning-border) bg-(--alert-warning-bg) text-(--alert-warning-fg)";

/**
 * What a discount waits for, in text, with a dot as a second carrier: an approved one is a filled green dot, one that waits for another person (its
 * creation or a proposal on it) a filled amber dot, and a rejected one a hollow grey dot. The text always carries the meaning.
 */
export function DiscountApprovalBadge({ state }: { readonly state: DiscountApprovalState }) {
  const { t } = useAdminText();
  const label = t(`scholarships.discounts.approval.${state}`);
  if (state === "approved") {
    return (
      <Badge variant="success" dot="filled">
        {label}
      </Badge>
    );
  }
  if (state === "pending" || state === "proposal") {
    return (
      <Badge variant="outline" dot="filled" className={WARNING}>
        {label}
      </Badge>
    );
  }
  return (
    <Badge variant="neutral" dot="hollow">
      {label}
    </Badge>
  );
}
