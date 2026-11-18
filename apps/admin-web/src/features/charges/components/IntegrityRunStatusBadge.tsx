import { Badge } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import type { IntegrityRunStatus } from "../charges-types.ts";

const DANGER = "border-(--alert-destructive-border) bg-(--alert-destructive-bg) text-(--alert-destructive-fg)";

/** The result of a run in text, with a dot as a second carrier: a clean run is a filled green dot, one with discrepancies a filled red dot. */
export function IntegrityRunStatusBadge({ status }: { readonly status: IntegrityRunStatus }) {
  const { t } = useAdminText();
  return status === "OK" ? (
    <Badge variant="success" dot="filled">
      {t("charges.integrity.statuses.OK")}
    </Badge>
  ) : (
    <Badge variant="outline" dot="filled" className={DANGER}>
      {t("charges.integrity.statuses.DISCREPANCIES")}
    </Badge>
  );
}
