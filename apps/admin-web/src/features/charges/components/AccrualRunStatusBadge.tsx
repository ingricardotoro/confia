import { Badge } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import type { AccrualRunStatus } from "../../../shared/api/charges-operations.ts";

const WARNING = "border-(--alert-warning-border) bg-(--alert-warning-bg) text-(--alert-warning-fg)";
const DANGER = "border-(--alert-destructive-border) bg-(--alert-destructive-bg) text-(--alert-destructive-fg)";

/**
 * The state of a run in text, with a dot as a second carrier: a queued run is hollow blue, one that is running filled blue,
 * a completed one filled green, one that completed with errors filled amber, and a rejected or failed one filled red.
 */
export function AccrualRunStatusBadge({ status }: { readonly status: AccrualRunStatus }) {
  const { t } = useAdminText();
  switch (status) {
    case "QUEUED":
      return (
        <Badge variant="primary" dot="hollow">
          {t("charges.accrual.statuses.QUEUED")}
        </Badge>
      );
    case "RUNNING":
      return (
        <Badge variant="primary" dot="filled">
          {t("charges.accrual.statuses.RUNNING")}
        </Badge>
      );
    case "COMPLETED":
      return (
        <Badge variant="success" dot="filled">
          {t("charges.accrual.statuses.COMPLETED")}
        </Badge>
      );
    case "COMPLETED_WITH_ERRORS":
      return (
        <Badge variant="outline" dot="filled" className={WARNING}>
          {t("charges.accrual.statuses.COMPLETED_WITH_ERRORS")}
        </Badge>
      );
    case "REJECTED":
      return (
        <Badge variant="outline" dot="hollow" className={DANGER}>
          {t("charges.accrual.statuses.REJECTED")}
        </Badge>
      );
    case "FAILED":
      return (
        <Badge variant="outline" dot="filled" className={DANGER}>
          {t("charges.accrual.statuses.FAILED")}
        </Badge>
      );
  }
}
