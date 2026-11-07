import type { AuthenticatedSessionResponseOutput } from "@confia/contracts/admin";
import type { ApiResult } from "../../../shared/api/http.ts";

/** Identifies the app and the function; it is not a secret (design.md D8). */
export const REFRESH_LOCK_NAME = "confia.admin.session.refresh";

/** What of `navigator.locks` the coordinator uses, so a test can stand in for the browser. */
export interface CrossTabLocks {
  request<T>(name: string, options: { readonly mode: "exclusive" }, callback: () => Promise<T>): Promise<T>;
}

export interface RefreshDependencies {
  readonly refresh: (csrfToken: string) => Promise<ApiResult<AuthenticatedSessionResponseOutput>>;
  /** Read at the moment the lock is granted, never earlier (invariant 3). */
  readonly readCsrf: () => string | undefined;
  /** Absent in a browser without Web Locks: the refresh then degrades to one promise per tab. */
  readonly locks: CrossTabLocks | undefined;
}

/** Every way a refresh ends, as a value: the coordinator does not throw for what the server said. */
export type RefreshOutcome =
  | { readonly kind: "refreshed"; readonly session: AuthenticatedSessionResponseOutput }
  /** No CSRF cookie when the lock was granted: nothing was sent. */
  | { readonly kind: "no-session" }
  /** No HTTP answer. It is never repeated: the server may have rotated the token already. */
  | { readonly kind: "network" }
  /** Any answer that is not a 200: `session-expired`, `csrf-invalid` or another. */
  | { readonly kind: "rejected" };

export interface RefreshCoordinator {
  /** At most one refresh in flight in this tab; every caller gets the outcome of the same one. */
  refreshOnce(): Promise<RefreshOutcome>;
}

/**
 * Runs `run` holding the exclusive lock, or just runs it where the browser has no Web Locks. Sign out
 * takes the same lock, so it never sends a cookie that a refresh in another tab is about to replace.
 */
export function withCrossTabLock<T>(locks: CrossTabLocks | undefined, run: () => Promise<T>): Promise<T> {
  return locks === undefined ? run() : locks.request(REFRESH_LOCK_NAME, { mode: "exclusive" }, run);
}

function classify(result: ApiResult<AuthenticatedSessionResponseOutput>): RefreshOutcome {
  if (result.kind === "ok") return { kind: "refreshed", session: result.data };
  return result.kind === "network" ? { kind: "network" } : { kind: "rejected" };
}

/**
 * One refresh at a time per tab, through a shared promise, and one at a time across tabs, through the
 * exclusive Web Lock (SS14 to SS16, SS18). There is no timer here: only a caller starts a refresh
 * (design.md D8).
 */
export function createRefreshCoordinator({ refresh, readCsrf, locks }: RefreshDependencies): RefreshCoordinator {
  let inFlight: Promise<RefreshOutcome> | undefined;

  async function attempt(): Promise<RefreshOutcome> {
    // Inside the lock: another tab may have rotated the cookie, or ended the session, while this one waited.
    const csrfToken = readCsrf();
    if (csrfToken === undefined) return { kind: "no-session" };
    return classify(await refresh(csrfToken));
  }

  return {
    refreshOnce() {
      inFlight ??= withCrossTabLock(locks, attempt).finally(() => {
        inFlight = undefined;
      });
      return inFlight;
    },
  };
}
