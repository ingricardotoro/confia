import * as api from "../../../shared/api/auth-operations.ts";
import { createAccessSession } from "./access-session.ts";
import type { CrossTabLocks } from "./refresh-coordinator.ts";
import { currentSessionQueryOptions } from "./current-session-query.ts";

/**
 * The one session of the tab, in module memory. It reads `navigator` and nothing else of the window: a
 * browser without Web Locks leaves `locks` undefined and the refresh degrades to one promise per tab.
 */
const locks: CrossTabLocks | undefined = "locks" in navigator ? navigator.locks : undefined;

export const accessSession = createAccessSession({ api, locks, now: () => Date.now() });

/** The options of the current session query, bound to the tab's session. */
export const currentSessionOptions = () => currentSessionQueryOptions(accessSession);

export { sessionKeys } from "./current-session-query.ts";
export type {
  AccessSession,
  ArrivalNotice,
  EnrollOutcome,
  SessionView,
  SignInOutcome,
  SignOutOutcome,
  VerifyOutcome,
} from "./access-session.ts";
