import { Badge } from "@confia/ui";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import type { AccrualOutcome } from "../../../shared/api/charges-operations.ts";
import { itemErrorKeyOf } from "../accrual-model.ts";

const DANGER = "border-(--alert-destructive-border) bg-(--alert-destructive-bg) text-(--alert-destructive-fg)";

export interface AccrualOutcomeBadgeProps {
  readonly outcome: AccrualOutcome;
  /** The error code of an item that ended in error. An item with an error is told by the code, in readable text. */
  readonly errorCode?: string | null | undefined;
  /** `preview` says what would happen ("Se creará"); `run` says what happened ("Creado"). */
  readonly mode: "preview" | "run";
}

/**
 * What happened, or would happen, to one student and concept: created, already accrued (by this plan or by another), or an error.
 * An error is told by its code in readable text, never by the raw code and never by colour alone: the dot is a second carrier.
 */
export function AccrualOutcomeBadge({ outcome, errorCode, mode }: AccrualOutcomeBadgeProps) {
  const { t } = useAdminText();
  if (outcome === "ERROR") {
    return (
      <Badge variant="outline" dot="filled" className={DANGER}>
        {t(itemErrorKeyOf(errorCode))}
      </Badge>
    );
  }
  const text = t(mode === "preview" ? `charges.accrual.outcomes.${outcome}` : `charges.accrual.runOutcomes.${outcome}`);
  return outcome === "CREATED" ? (
    <Badge variant="success" dot="filled">
      {text}
    </Badge>
  ) : (
    <Badge variant="neutral" dot="hollow">
      {text}
    </Badge>
  );
}
