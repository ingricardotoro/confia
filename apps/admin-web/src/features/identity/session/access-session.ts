import type { CurrentSessionResponseOutput } from "@confia/contracts/admin";
import { ApiFailure, type ApiFailureResult } from "../../../shared/api/api-failure.ts";
import type * as authOperations from "../../../shared/api/auth-operations.ts";
import type { Credentials, SecondFactorProof } from "../../../shared/api/auth-operations.ts";
import { readCsrfToken } from "../../../shared/api/csrf-cookie.ts";
import {
  createEnrollmentDraft,
  type EnrollmentDraft,
  type EnrollmentSnapshot,
  type EnrollmentStep,
  type StartEnrollmentOutcome,
} from "./enrollment-draft.ts";
import { createRefreshCoordinator, withCrossTabLock, type CrossTabLocks } from "./refresh-coordinator.ts";
import { createTokenStore, type SecondFactorUse } from "./token-store.ts";

/** The six operations of access; the real ones in the app, the same ones over the test server in tests. */
export type AccessApi = Pick<
  typeof authOperations,
  "logIn" | "refreshSession" | "endCurrentSession" | "getCurrentSession" | "enrollSecondFactor" | "completeSecondFactor"
>;

/** Why a person arrives at the sign in page, when it is not by choice. */
export type ArrivalNotice =
  | "session-expired"
  | "second-factor-interrupted"
  | "enrollment-interrupted"
  | "second-factor-already-enrolled";

/** What the interface may know about the session. It never holds a token (design.md D8). */
export type SessionView =
  | { readonly status: "restoring" }
  | { readonly status: "restore-failed" }
  | { readonly status: "anonymous"; readonly notice?: ArrivalNotice }
  | { readonly status: "authenticated" };

export type SignInOutcome =
  | { readonly kind: "authenticated" }
  | { readonly kind: "second-factor-required" }
  | { readonly kind: "enrollment-required" }
  | { readonly kind: "failed"; readonly result: ApiFailureResult };

export type VerifyOutcome =
  | { readonly kind: "authenticated" }
  | { readonly kind: "no-token" }
  | { readonly kind: "failed"; readonly result: ApiFailureResult };

export type { EnrollmentSnapshot, EnrollmentStep, StartEnrollmentOutcome };

export type SignOutOutcome = { readonly kind: "signed-out" } | { readonly kind: "failed" };

export interface AccessSession {
  /** For `useSyncExternalStore`. */
  subscribe(listener: () => void): () => void;
  /** The same object until the view changes. */
  getView(): SessionView;
  boot(): Promise<void>;
  retryBoot(): Promise<void>;
  signIn(credentials: Credentials): Promise<SignInOutcome>;
  hasSecondFactorToken(use: SecondFactorUse): boolean;
  verify(proof: SecondFactorProof): Promise<VerifyOutcome>;
  /** One request per restricted token: a second call shares the first one's promise (SS74). */
  startEnrollment(): Promise<StartEnrollmentOutcome>;
  /** The same object until the enrollment changes; `undefined` before the secret arrives and after the draft is cleared. */
  getEnrollment(): EnrollmentSnapshot | undefined;
  advanceEnrollment(): void;
  reviewEnrollmentSecret(): void;
  /** The first code of an enrollment, only as a TOTP code and with the `mfa-enroll` token (SS72, SS96). */
  activate(code: string): Promise<VerifyOutcome>;
  discardSecondFactor(notice?: ArrivalNotice): void;
  /** Throws `ApiFailure`; it is the query function of the current session. */
  readCurrentSession(): Promise<CurrentSessionResponseOutput>;
  signOut(): Promise<SignOutOutcome>;
}

export interface AccessSessionDependencies {
  readonly api: AccessApi;
  /** `navigator.locks`, or `undefined` in a browser that has none. */
  readonly locks: CrossTabLocks | undefined;
  /** The clock, in milliseconds. */
  readonly now: () => number;
  /** Reads the CSRF cookie; the default is the app's one reader. */
  readonly readCsrf?: () => string | undefined;
  /** The draft of the enrollment; the default is a new one. Tests pass theirs to look inside it. */
  readonly enrollmentDraft?: EnrollmentDraft;
}

/** A request with a `Bearer` token refreshes first when less than this is left of it (SS22, SS23). */
const REFRESH_MARGIN_MS = 60_000;

/**
 * What a read throws when the session ended and no request could, or should, be made. It is the shape
 * of the server's own answer for an expired session, so a caller handles one case.
 */
const SESSION_ENDED: ApiFailureResult = {
  kind: "problem",
  problem: { status: 401, code: "session-expired", errors: [] },
};

/**
 * The session: tokens in module memory, one refresh at a time, and no timer (design.md D8). Only a
 * caller starts a refresh: `boot`, `retryBoot`, or a read with less than a minute left on the token.
 */
export function createAccessSession({
  api,
  locks,
  now,
  readCsrf = () => readCsrfToken(),
  enrollmentDraft = createEnrollmentDraft(),
}: AccessSessionDependencies): AccessSession {
  const store = createTokenStore();
  const coordinator = createRefreshCoordinator({ refresh: api.refreshSession, readCsrf, locks });
  const listeners = new Set<() => void>();
  let view: SessionView = { status: "restoring" };
  let booting: Promise<void> | undefined;
  /** The enrollment request of the restricted token held, so a second call and React's double mount share it. */
  let starting: { readonly token: string; readonly promise: Promise<StartEnrollmentOutcome> } | undefined;

  function notify(): void {
    for (const listener of [...listeners]) listener();
  }

  function show(next: SessionView): void {
    view = next;
    notify();
  }

  /** Runs a change of the draft and tells the listeners only if it changed something. */
  function changeDraft(change: () => void): void {
    const before = enrollmentDraft.snapshot();
    change();
    if (enrollmentDraft.snapshot() !== before) notify();
  }

  function forgetEnrollment(): void {
    enrollmentDraft.clear();
    starting = undefined;
  }

  /** Forgets the tokens and the enrollment and shows the sign in page. The query cache is cleared by `app/`, which sees the view. */
  function endLocally(notice?: ArrivalNotice): void {
    store.clear();
    forgetEnrollment();
    show(notice === undefined ? { status: "anonymous" } : { status: "anonymous", notice });
  }

  async function restore(): Promise<void> {
    if (view.status !== "restoring") show({ status: "restoring" });
    const outcome = await coordinator.refreshOnce();
    switch (outcome.kind) {
      case "refreshed":
        store.accept(outcome.session);
        show({ status: "authenticated" });
        return;
      case "no-session":
        // Nobody signed in here: arriving without a cookie is not an expired session.
        endLocally();
        return;
      case "network":
        show({ status: "restore-failed" });
        return;
      case "rejected":
        endLocally("session-expired");
    }
  }

  /** The token for a request, refreshed first when less than a minute is left. A failed refresh is not repeated. */
  async function accessTokenForRequest(): Promise<string> {
    const held = store.accessToken();
    if (held === undefined) {
      // Only an open session can expire. With none open (signed out, never signed in, or still
      // restoring) the read fails and the view is left as it is: arriving without a session is not
      // an expired session.
      if (view.status === "authenticated") endLocally("session-expired");
      throw new ApiFailure(SESSION_ENDED);
    }
    if (held.expiresAt - now() < REFRESH_MARGIN_MS) {
      const outcome = await coordinator.refreshOnce();
      if (outcome.kind === "refreshed") {
        store.accept(outcome.session);
        return outcome.session.accessToken;
      }
      // On a network failure the person's request does not go out and the session is kept: the server
      // may or may not have rotated the cookie, and only the person decides to try again.
      if (outcome.kind === "network") throw new ApiFailure({ kind: "network" });
      endLocally("session-expired");
      throw new ApiFailure(SESSION_ENDED);
    }
    return held.token;
  }

  function secondFactorTokenFor(use: SecondFactorUse): string | undefined {
    const held = store.secondFactorToken();
    return held?.use === use && held.expiresAt > now() ? held.token : undefined;
  }

  return {
    subscribe(listener) {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    getView: () => view,
    boot: () => (booting ??= restore()),
    retryBoot() {
      if (view.status !== "restore-failed") return Promise.resolve();
      booting = restore();
      return booting;
    },
    async signIn(credentials) {
      const result = await api.logIn(credentials);
      if (result.kind !== "ok") return { kind: "failed", result };
      store.accept(result.data);
      // Only an enrollment keeps the identifier (SS58, SS59); whatever an earlier sign in left is gone.
      // Through changeDraft, so a listener hears the change even on the answers that show no new view.
      const { status } = result.data;
      changeDraft(() => {
        forgetEnrollment();
        if (status === "second-factor-enrollment-required") enrollmentDraft.begin(credentials.identifier);
      });
      if (status === "authenticated") {
        show({ status: "authenticated" });
        return { kind: "authenticated" };
      }
      return { kind: status === "second-factor-required" ? "second-factor-required" : "enrollment-required" };
    },
    hasSecondFactorToken: (use) => secondFactorTokenFor(use) !== undefined,
    async verify(proof) {
      const token = secondFactorTokenFor("mfa-verify");
      if (token === undefined) return { kind: "no-token" };
      const result = await api.completeSecondFactor(token, proof);
      if (result.kind !== "ok") return { kind: "failed", result };
      store.accept(result.data);
      forgetEnrollment();
      show({ status: "authenticated" });
      return { kind: "authenticated" };
    },
    startEnrollment() {
      const token = secondFactorTokenFor("mfa-enroll");
      if (token === undefined) return Promise.resolve({ kind: "no-token" });
      if (starting?.token === token) return starting.promise;
      const promise = (async (): Promise<StartEnrollmentOutcome> => {
        // `shared/api` returns results and never throws; if a call still does, it ends as a failure the person can
        // retry, and the rejected promise is not kept, or every retry would return the same rejection.
        const result = await api.enrollSecondFactor(token).catch((): "threw" => "threw");
        if (result === "threw") {
          if (starting?.token === token) starting = undefined;
          return { kind: "failed", result: { kind: "network" } };
        }
        // Abandoned while in flight: the answer belongs to nobody.
        if (starting?.token !== token) return { kind: "no-token" };
        if (result.kind !== "ok") {
          // Forgotten, so only the person's retry sends the next request; the token stays (SS87).
          starting = undefined;
          return { kind: "failed", result };
        }
        changeDraft(() => {
          enrollmentDraft.receive(result.data.secret, result.data.recoveryCodes);
        });
        return { kind: "ready" };
      })();
      starting = { token, promise };
      return promise;
    },
    getEnrollment: () => enrollmentDraft.snapshot(),
    advanceEnrollment() {
      changeDraft(() => {
        enrollmentDraft.advance();
      });
    },
    reviewEnrollmentSecret() {
      changeDraft(() => {
        enrollmentDraft.review();
      });
    },
    async activate(code) {
      const token = secondFactorTokenFor("mfa-enroll");
      if (token === undefined) {
        // An open session has nothing to interrupt; otherwise the enrollment is over.
        if (store.accessToken() === undefined) endLocally("enrollment-interrupted");
        return { kind: "no-token" };
      }
      const result = await api.completeSecondFactor(token, { kind: "code", value: code });
      if (result.kind !== "ok") {
        // The restricted token ended: the enrollment is over, and a new sign in starts a new secret (SS100).
        const code = result.kind === "problem" ? result.problem.code : undefined;
        if (code === "token-expired" || code === "token-invalid") endLocally("enrollment-interrupted");
        return { kind: "failed", result };
      }
      store.accept(result.data);
      forgetEnrollment();
      show({ status: "authenticated" });
      return { kind: "authenticated" };
    },
    discardSecondFactor(notice) {
      if (store.accessToken() === undefined) endLocally(notice);
    },
    async readCurrentSession() {
      const result = await api.getCurrentSession(await accessTokenForRequest());
      if (result.kind === "ok") return result.data;
      // A 401 to a request with a token ends the session here: no refresh to try again (SS25).
      if (result.kind === "problem" && result.problem.status === 401) endLocally("session-expired");
      throw new ApiFailure(result);
    },
    async signOut() {
      // Inside the lock, so the cookie is the one a refresh in another tab left (design.md D8).
      const result = await withCrossTabLock(locks, () => api.endCurrentSession(readCsrf()));
      if (result.kind !== "ok" || result.status !== 204) return { kind: "failed" };
      endLocally();
      return { kind: "signed-out" };
    },
  };
}
