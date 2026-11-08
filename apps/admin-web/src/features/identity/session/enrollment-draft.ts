import type { ApiFailureResult } from "../../../shared/api/api-failure.ts";

/**
 * The draft of a second factor enrollment, in module memory and nowhere else: the identifier the person
 * signed in with, the secret, the recovery codes and the step. Like `token-store.ts`, only the session
 * module reaches this file (the dependency rule `admin-token-store-is-private`), so one `endLocally`
 * forgets it on every path (design.md E2; SS98). It holds no token: a token lives in the token store.
 */
export type EnrollmentStep = "secret" | "recovery-codes" | "first-code";

export interface EnrollmentSnapshot {
  /** The value sent to sign in, already trimmed. */
  readonly identifier: string;
  /** Base32, as it arrived. */
  readonly secret: string;
  /** `undefined` from the moment the person confirmed having saved them (SS93). */
  readonly recoveryCodes: readonly string[] | undefined;
  readonly step: EnrollmentStep;
}

export type StartEnrollmentOutcome =
  | { readonly kind: "ready" }
  | { readonly kind: "no-token" }
  | { readonly kind: "failed"; readonly result: ApiFailureResult };

export interface EnrollmentDraft {
  /** Starts a new draft with the identifier alone; whatever an earlier draft held is gone. */
  begin(identifier: string): void;
  /** Keeps the secret and the codes at the first step. Does nothing without an identifier. */
  receive(secret: string, recoveryCodes: readonly string[]): void;
  /** `secret` to `recovery-codes` (or straight to `first-code` once the codes are gone), then to `first-code`. */
  advance(): void;
  /** `first-code` back to `secret`; any other step stays. */
  review(): void;
  /** `undefined` until the secret arrives. The same object until the draft changes. */
  snapshot(): EnrollmentSnapshot | undefined;
  identifier(): string | undefined;
  clear(): void;
}

export function createEnrollmentDraft(): EnrollmentDraft {
  let identifier: string | undefined;
  let current: EnrollmentSnapshot | undefined;

  return {
    begin(next) {
      identifier = next.trim();
      current = undefined;
    },
    receive(secret, recoveryCodes) {
      if (identifier === undefined) return;
      current = { identifier, secret, recoveryCodes, step: "secret" };
    },
    advance() {
      if (current === undefined || current.step === "first-code") return;
      // Leaving recovery-codes drops the codes: nothing afterwards returns them (SS93).
      const next: EnrollmentSnapshot =
        current.step === "secret" && current.recoveryCodes !== undefined
          ? { ...current, step: "recovery-codes" }
          : { ...current, step: "first-code", recoveryCodes: undefined };
      current = next;
    },
    review() {
      if (current?.step !== "first-code") return;
      current = { ...current, step: "secret" };
    },
    snapshot: () => current,
    identifier: () => identifier,
    clear() {
      identifier = undefined;
      current = undefined;
    },
  };
}
