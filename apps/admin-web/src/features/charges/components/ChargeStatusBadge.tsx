import { Badge } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import type { StudentChargeStatus } from "../charges-types.ts";

/** The state of a charge in text, with a dot as a second carrier: a charge in force is a filled green dot, a voided one a hollow grey dot. */
export function ChargeStatusBadge({ status }: { readonly status: StudentChargeStatus }) {
  const { t } = useAdminText();
  return status === "VOIDED" ? (
    <Badge variant="outline" dot="hollow">
      {t("charges.studentCharges.statuses.VOIDED")}
    </Badge>
  ) : (
    <Badge variant="success" dot="filled">
      {t("charges.studentCharges.statuses.ACTIVE")}
    </Badge>
  );
}
