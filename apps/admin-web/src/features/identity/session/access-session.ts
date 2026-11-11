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
import { createDeadlineStore, type LocalDeadlines } from "./deadline-store.ts";
import { createServerClock } from "./lifecycle/server-clock.ts";
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

/** How an extension of the session ended: it was extended, the network failed (nothing changes) or the session ended. */
export type ExtendOutcome = { readonly kind: "extended" } | { readonly kind: "network" } | { readonly kind: "ended" };

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
  /**
   * The one way to extend the session: the refresh of the coordinator, shared with every other trigger
   * (SS146, SS162). It sends nothing when the session is not complete. A network failure keeps the session
   * and is not repeated (SS148); any other refusal ends it (SS149, SS164).
   */
  extend(): Promise<ExtendOutcome>;
  /**
   * Ends the session because a deadline passed (H6): the local session first, so nothing in between sees an
   * expired one, then a best effort `DELETE` that is not awaited, not repeated and not shown when it fails
   * (SS167, SS168). Does nothing when the session is not complete.
   */
  endByTimeout(reason: "idle" | "absolute"): void;
  /** The deadlines in local milliseconds, or `undefined` when there is no complete session. Not a secret (H1). */
  getDeadlines(): LocalDeadlines | undefined;
  subscribeDeadlines(listener: () => void): () => void;
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
 * The session: tokens in module memory and one refresh at a time (design.md D8). No timer and no
 * background query asks for a refresh (admin-web-shell-and-session-lifecycle, design.md section 2, SS19
 * precised): only a caller starts one: `boot`, `retryBoot`, a read with less than a minute left on the
 * token, or `extend`, which the person's activity and the warning of inactivity use.
 */
export function createAccessSession({
  api,
  locks,
  now,
  readCsrf = () => readCsrfToken(),
  enrollmentDraft = createEnrollmentDraft(),
}: AccessSessionDependencies): AccessSession {
  const store = createTokenStore();
  const deadlines = createDeadlineStore();
  const clock = createServerClock();
  const coordinator = createRefreshCoordinator({ refresh: api.refreshSession, readCsrf, locks });
  const listeners = new Set<() => void>();
  let view: SessionView = { status: "restoring" };
  let booting: Promise<void> | undefined;
  /** The enrollment request of the restricted token held, so a second call and React's double mount share it. */
  let starting: { readonly token: string; readonly promise: Promise<StartEnrollmentOutcome> } | undefined;

  /** A function, so the narrowing of `view` does not outlive an `await`. */
  const isAuthenticated = (): boolean => view.status === "authenticated";

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
    deadlines.clear();
    show(notice === undefined ? { status: "anonymous" } : { status: "anonymous", notice });
  }

  /**
   * Keeps the deadlines of an answer, converted with the clock of the server (H1, H3). `refreshed` is true
   * for a refresh, which also marks the instant the cadence of activity counts from.
   */
  function recordDeadlines(
    data: { readonly idleExpiresAt: string; readonly sessionExpiresAt: string },
    arrival: { readonly serverDate: number | undefined; readonly receivedAt: number },
    refreshed: boolean,
  ): void {
    clock.observe(arrival.serverDate, arrival.receivedAt);
    deadlines.record(
      { idleExpiresAt: clock.toLocal(data.idleExpiresAt), sessionExpiresAt: clock.toLocal(data.sessionExpiresAt) },
      refreshed ? arrival.receivedAt : undefined,
    );
  }

  async function restore(): Promise<void> {
    if (view.status !== "restoring") show({ status: "restoring" });
    const outcome = await coordinator.refreshOnce();
    switch (outcome.kind) {
      case "refreshed":
        store.accept(outcome.session);
        recordDeadlines(outcome.session, outcome, true);
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
        recordDeadlines(outcome.session, outcome, true);
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
      if (result.data.status === "authenticated") recordDeadlines(result.data, result, false);
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
      recordDeadlines(result.data, result, false);
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
      recordDeadlines(result.data, result, false);
      forgetEnrollment();
      show({ status: "authenticated" });
      return { kind: "authenticated" };
    },
    discardSecondFactor(notice) {
      if (store.accessToken() === undefined) endLocally(notice);
    },
    async readCurrentSession() {
      const result = await api.getCurrentSession(await accessTokenForRequest());
      if (result.kind === "ok") {
        recordDeadlines(result.data, result, false);
        return result.data;
      }
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
    async extend() {
      if (!isAuthenticated()) return { kind: "ended" };
      const outcome = await coordinator.refreshOnce();
      // The session may have ended while the refresh was in flight: its answer belongs to nobody.
      if (!isAuthenticated()) return { kind: "ended" };
      switch (outcome.kind) {
        case "refreshed":
          store.accept(outcome.session);
          recordDeadlines(outcome.session, outcome, true);
          return { kind: "extended" };
        case "network":
          // Not repeated and not shown: the server may or may not have rotated the cookie (SS148).
          return { kind: "network" };
        case "no-session":
        case "rejected":
          endLocally("session-expired");
          return { kind: "ended" };
      }
    },
    endByTimeout() {
      if (view.status !== "authenticated") return;
      // The local session first, so no interaction in between sees an expired one; the DELETE is best effort.
      endLocally("session-expired");
      void withCrossTabLock(locks, () => api.endCurrentSession(readCsrf())).catch(() => undefined);
    },
    getDeadlines: () => deadlines.snapshot(),
    subscribeDeadlines: (listener) => deadlines.subscribe(listener),
  };
}
