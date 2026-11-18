import { Badge } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import type { LineDirection } from "../statement-model.ts";

export interface StatementDirectionBadgeProps {
  readonly direction: LineDirection;
}

/**
 * Whether a line of the statement raises what the student owes («Cargo») or lowers it («Abono»). It is told in text with a dot
 * as a second carrier, never by colour alone; which column holds the figure is the server's, and the badge only names it.
 */
export function StatementDirectionBadge({ direction }: StatementDirectionBadgeProps) {
  const { t } = useAdminText();
  return direction === "debit" ? (
    <Badge variant="neutral" size="sm" dot="filled">
      {t("charges.statement.direction.debit")}
    </Badge>
  ) : (
    <Badge variant="success" size="sm" dot="hollow">
      {t("charges.statement.direction.credit")}
    </Badge>
  );
}
