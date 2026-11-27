import { useRef } from "react";
import type { AccessFailure } from "../../shared/forms/access-failure.ts";
import { intentFor, type IdempotentIntent } from "../../shared/forms/idempotent-intent.ts";

/** What a failed decision holds: the failure a dialog shows, and whether the server may have done it (so the same intent is sent again). */
export interface DecisionFailure {
  readonly failure: AccessFailure;
  readonly outcomeUnknown: boolean;
}

/**
 * The `Idempotency-Key` of a decision the person made, shared by every decision of the scholarships (the assignments and the discounts). The key is made
 * once for the intent (its fingerprint: the kind of decision, what it is about and what it sends) and travels again unchanged while the outcome is
 * unknown (the network failed, the answer made no sense, the server failed); it is renewed after a definite refusal, and after the server confirms.
 * Returns the failure to show, or `undefined` once the server confirmed (after calling `onDecided`).
 */
export function useDecisionIntent(failureOf: (error: unknown) => DecisionFailure) {
  const intent = useRef<IdempotentIntent | undefined>(undefined);
  return async function decide(fingerprint: string, send: (idempotencyKey: string) => Promise<unknown>, onDecided: () => void): Promise<AccessFailure | undefined> {
    const current = intentFor(intent.current, fingerprint);
    intent.current = current;
    try {
      await send(current.idempotencyKey);
    } catch (error) {
      const { failure, outcomeUnknown } = failureOf(error);
      if (!outcomeUnknown) intent.current = undefined;
      return failure;
    }
    intent.current = undefined;
    onDecided();
    return undefined;
  };
}
