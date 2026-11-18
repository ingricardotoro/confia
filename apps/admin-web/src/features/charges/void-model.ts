/** The bounds of the reason of a void, as the server holds them (20 to 500 characters once stripped of border spaces). */
export const VOID_REASON_MIN = 20;
export const VOID_REASON_MAX = 500;

/** Control and format characters, which the server refuses (a line break is one of them). */
const CONTROL_OR_FORMAT = /[\p{Cc}\p{Cf}]/u;

/** The reason as it is sent: without the spaces at its borders, like the server strips it. */
export const strippedReasonOf = (reason: string): string => reason.trim();

/** How many characters the server counts: code points, so an emoji counts once. The counter and the bounds read this. */
export const reasonLengthOf = (reason: string): number => Array.from(strippedReasonOf(reason)).length;

export type ReasonProblem = "tooShort" | "tooLong" | "control";

/** What is wrong with a reason, or nothing. The server decides; this only spares the person the obvious refusal. */
export function reasonProblemOf(reason: string): ReasonProblem | undefined {
  const length = reasonLengthOf(reason);
  if (length < VOID_REASON_MIN) return "tooShort";
  if (length > VOID_REASON_MAX) return "tooLong";
  if (CONTROL_OR_FORMAT.test(strippedReasonOf(reason))) return "control";
  return undefined;
}

/**
 * What the person confirmed: a charge and the reason. The key is made once for the intent and sent on every try of it, so a retry after
 * a network failure (the server may or may not have voided the charge) answers the same void and never a second one. A reason that
 * changed is another request body, so it gets its own key: the server answers the same key with another body with a refusal.
 */
export interface VoidIntent {
  readonly chargeId: string;
  readonly reason: string;
  readonly idempotencyKey: string;
}

/** The intent to send now: the previous one when the charge and the reason are the same, a new one (with a new key) otherwise. */
export function intentFor(previous: VoidIntent | undefined, chargeId: string, reason: string, random: () => string = () => crypto.randomUUID()): VoidIntent {
  const stripped = strippedReasonOf(reason);
  if (previous !== undefined && previous.chargeId === chargeId && previous.reason === stripped) return previous;
  return { chargeId, reason: stripped, idempotencyKey: random() };
}
