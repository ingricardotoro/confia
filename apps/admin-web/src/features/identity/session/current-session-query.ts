import { queryOptions } from "@tanstack/react-query";
import type { AccessSession } from "./access-session.ts";

/** Query keys of the session, general to specific, so invalidating by prefix works. */
export const sessionKeys = {
  all: ["identity", "session"] as const,
  current: () => [...sessionKeys.all, "current"] as const,
};

/**
 * `GET /auth/sessions/current` as a query. It caches metadata only (the response carries no token), and
 * it never runs by itself: no interval, no refetch on focus or on reconnect (SS21; docs/ui-ux/04, §7).
 * It does not retry either. A retried read would retry the refresh that comes before it, and reusing a
 * refresh cookie outside the grace period revokes the whole session family (SS24).
 */
export function currentSessionQueryOptions(session: Pick<AccessSession, "readCurrentSession">) {
  return queryOptions({
    queryKey: sessionKeys.current(),
    queryFn: () => session.readCurrentSession(),
    retry: false,
    refetchInterval: false,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });
}
