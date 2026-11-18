import { Badge } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import type { ChargePlanStatus } from "../charges-types.ts";

/** The state of a plan in text, with a dot as a second carrier: a draft is hollow blue, an active plan filled green, an archived one hollow grey. */
export function ChargePlanStatusBadge({ status }: { readonly status: ChargePlanStatus }) {
  const { t } = useAdminText();
  const look = status === "ACTIVE" ? ({ variant: "success", dot: "filled" } as const) : status === "DRAFT" ? ({ variant: "primary", dot: "hollow" } as const) : ({ variant: "neutral", dot: "hollow" } as const);
  return (
    <Badge variant={look.variant} dot={look.dot}>
      {t(`charges.plans.statuses.${status}`)}
    </Badge>
  );
}
