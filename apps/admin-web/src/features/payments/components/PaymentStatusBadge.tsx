import { Badge } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import type { PaymentStatus } from "../payments-types.ts";

const WARNING = "border-(--alert-warning-border) bg-(--alert-warning-bg) text-(--alert-warning-fg)";

/**
 * The state of a payment in text, with a dot as a second carrier: confirmed is a filled green dot, a transfer waiting for its confirmation a
 * filled amber dot, and a rejected payment a hollow grey dot. The text is always the carrier of the meaning.
 */
export function PaymentStatusBadge({ status, size = "sm" }: { readonly status: PaymentStatus; readonly size?: "sm" | "md" }) {
  const { t } = useAdminText();
  const label = t(`payments.statuses.${status}`);
  if (status === "confirmed") {
    return (
      <Badge variant="success" dot="filled" size={size}>
        {label}
      </Badge>
    );
  }
  if (status === "declared") {
    return (
      <Badge variant="outline" dot="filled" size={size} className={WARNING}>
        {label}
      </Badge>
    );
  }
  return (
    <Badge variant="neutral" dot="hollow" size={size}>
      {label}
    </Badge>
  );
}
