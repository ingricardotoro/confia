import { Badge } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import type { AwardStatus } from "../scholarships-types.ts";

const WARNING = "border-(--alert-warning-border) bg-(--alert-warning-bg) text-(--alert-warning-fg)";

/**
 * The state of an award in text, with a dot as a second carrier: an approved award is a filled green dot, one waiting for its decision a filled
 * amber dot, and a rejected or revoked one a hollow grey dot. The text is always the carrier of the meaning.
 */
export function AwardStatusBadge({ status, size = "sm" }: { readonly status: AwardStatus; readonly size?: "sm" | "md" }) {
  const { t } = useAdminText();
  const label = t(`scholarships.statuses.${status}`);
  if (status === "approved") {
    return (
      <Badge variant="success" dot="filled" size={size}>
        {label}
      </Badge>
    );
  }
  if (status === "pending") {
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
