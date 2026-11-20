import { Badge } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import type { CashSessionStatus } from "../cashbox-types.ts";

const WARNING = "border-(--alert-warning-border) bg-(--alert-warning-bg) text-(--alert-warning-fg)";

/**
 * The state of a session in text, with a dot as a second carrier: open is a filled blue dot, waiting for an approval a filled amber dot,
 * and closed a hollow one. The text is always the carrier of the meaning.
 */
export function CashSessionStatusBadge({ status, size = "sm" }: { readonly status: CashSessionStatus; readonly size?: "sm" | "md" }) {
  const { t } = useAdminText();
  const label = t(`cashbox.statuses.${status}`);
  if (status === "open") {
    return (
      <Badge variant="primary" dot="filled" size={size}>
        {label}
      </Badge>
    );
  }
  if (status === "pending_approval") {
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
