import type { LoginOutcome } from "../../../shared/api/auth-operations.ts";

/**
 * The tokens of the session, in module memory and nowhere else: not in storage, not in the query cache,
 * not in a context (CLAUDE.md rules 9 to 11; the dependency rule `admin-token-store-is-private` lets
 * only this folder import this file). It takes a contract response as it came and keeps one kind of
 * token at a time. It has no entry for an identifier or a password (SS59).
 */
export type SecondFactorUse = "mfa-verify" | "mfa-enroll";

export interface HeldAccessToken {
  readonly token: string;
  /** When it stops being valid, as an instant in milliseconds. */
  readonly expiresAt: number;
}

export interface HeldSecondFactorToken {
  readonly token: string;
  readonly expiresAt: number;
  readonly use: SecondFactorUse;
}

export interface TokenStore {
  /** Keeps what the response carries and forgets the other kind of token. */
  accept(outcome: LoginOutcome): void;
  accessToken(): HeldAccessToken | undefined;
  secondFactorToken(): HeldSecondFactorToken | undefined;
  clear(): void;
}

export function createTokenStore(): TokenStore {
  let access: HeldAccessToken | undefined;
  let secondFactor: HeldSecondFactorToken | undefined;

  return {
    accept(outcome) {
      if (outcome.status === "authenticated") {
        access = { token: outcome.accessToken, expiresAt: Date.parse(outcome.accessTokenExpiresAt) };
        secondFactor = undefined;
        return;
      }
      access = undefined;
      secondFactor = {
        token: outcome.secondFactorToken,
        expiresAt: Date.parse(outcome.secondFactorTokenExpiresAt),
        use: outcome.status === "second-factor-required" ? "mfa-verify" : "mfa-enroll",
      };
    },
    accessToken: () => access,
    secondFactorToken: () => secondFactor,
    clear() {
      access = undefined;
      secondFactor = undefined;
    },
  };
}
