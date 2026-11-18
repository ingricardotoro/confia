import { useState } from "react";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { ApiFailure } from "../../shared/api/api-failure.ts";
import { failureMessageOf, failureOf } from "../../shared/resource-screen/resource-failure.ts";
import { VoidChargeDialog, VOID_REASON_ID } from "./components/VoidChargeDialog.tsx";
import type { StudentCharge } from "./charges-types.ts";
import { useVoidCharge } from "./use-student-charge-queries.ts";
import { intentFor, reasonProblemOf, type VoidIntent } from "./void-model.ts";

export interface VoidChargeContainerProps {
  readonly charge: StudentCharge;
  readonly onClose: () => void;
  /** The server confirmed the void. The lists and statements are already read again. */
  readonly onVoided: () => void;
}

/** The failure of the last try of a confirmation, ready for the dialog. */
interface VoidFailure {
  readonly message: string;
  readonly traceId?: string | undefined;
  /** The server may not have received the request (the network failed or the answer made no sense): the same intent can be sent again. */
  readonly retryable: boolean;
}

/**
 * The void of one charge. The intent (the charge, the reason and the `Idempotency-Key`) is made when the person confirms and sent
 * again unchanged if the answer never arrived; a reason that changed, or a key the server refused for another body, is a new intent
 * with a new key. One try at a time, nothing optimistic, and every refusal of the server is told in the dialog.
 */
export function VoidChargeContainer({ charge, onClose, onVoided }: VoidChargeContainerProps) {
  const { t } = useAdminText();
  const voiding = useVoidCharge();
  const [reason, setReason] = useState("");
  const [touched, setTouched] = useState(false);
  const [intent, setIntent] = useState<VoidIntent>();
  const [failure, setFailure] = useState<VoidFailure>();

  async function confirm() {
    // One try at a time: a second press while the first is in flight sends nothing.
    if (voiding.isPending) return;
    if (reasonProblemOf(reason) !== undefined) {
      setTouched(true);
      document.getElementById(VOID_REASON_ID)?.focus();
      return;
    }
    const current = intentFor(intent, charge.id, reason);
    setIntent(current);
    setFailure(undefined);
    try {
      await voiding.mutateAsync(current);
      onVoided();
    } catch (error) {
      const message = failureMessageOf(failureOf(error));
      const problemCode = error instanceof ApiFailure && error.result.kind === "problem" ? error.result.problem.code : undefined;
      // The key was used for another body: the next try is another request, with its own key.
      if (problemCode === "idempotency-payload-mismatch") setIntent(undefined);
      const unsure = error instanceof ApiFailure && (error.result.kind === "network" || error.result.kind === "unexpected-response");
      setFailure({ message: t(message.key), traceId: message.traceId, retryable: unsure });
    }
  }

  return (
    <VoidChargeDialog
      charge={charge}
      reason={reason}
      onReasonChange={setReason}
      problem={touched ? reasonProblemOf(reason) : undefined}
      busy={voiding.isPending}
      failure={failure}
      onReasonBlur={() => {
        setTouched(true);
      }}
      onConfirm={() => void confirm()}
      onCancel={onClose}
    />
  );
}
