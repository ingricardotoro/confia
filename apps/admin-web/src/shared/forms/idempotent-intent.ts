/**
 * What the person confirmed (a cash movement, a payment, a decision), as a fingerprint of the operation, the session and the body. The key is made once for the intent and
 * sent on every try of it, so a retry after a network failure (the server may or may not have done it) answers the same request and
 * never a second one. Anything that changed is another request body, so it gets its own key: the server answers the same key with
 * another body with a refusal.
 */
export interface IdempotentIntent {
  readonly fingerprint: string;
  readonly idempotencyKey: string;
}

/** The intent to send now: the previous one when it is the same request, a new one (with a new key) otherwise. */
export function intentFor(previous: IdempotentIntent | undefined, fingerprint: string, random: () => string = () => crypto.randomUUID()): IdempotentIntent {
  return previous?.fingerprint === fingerprint ? previous : { fingerprint, idempotencyKey: random() };
}
