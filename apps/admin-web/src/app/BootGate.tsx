import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useState, type ReactNode } from "react";
import { BootScreen } from "../features/identity/boot/components/BootScreen.tsx";
import { useSessionView } from "../features/identity/boot/use-session-view.ts";
import type { AccessSession } from "../features/identity/session/index.ts";
import { readCsrfToken } from "../shared/api/csrf-cookie.ts";

export interface BootGateProps {
  readonly session: AccessSession;
  /** The CSRF cookie reader; the default is the one reader of the app. A test gives its own. */
  readonly readCsrf?: (() => string | undefined) | undefined;
  readonly children: ReactNode;
}

/**
 * The boot is not a route but a gate above the router (design.md D5): the same behavior from any URL, and
 * no guard has to wait for a promise. Without the CSRF cookie there is nothing to restore, so the router
 * mounts on the first render; with it, the restoring screen shows until the one refresh answers, and a
 * failure by network stays on screen with a retry. When the session ends, the query cache is emptied.
 */
export function BootGate({ session, readCsrf = readCsrfToken, children }: BootGateProps) {
  const view = useSessionView(session);
  const queryClient = useQueryClient();
  // Read once, at the first render: the cookie tells whether a restoration is going to happen at all.
  const [hasCookie] = useState(() => readCsrf() !== undefined);

  // With no cookie the boot is not started at all: arriving without a session is not an event of the
  // session, and a boot that ended afterwards would clear the notice or the restricted token that a guard
  // had just put there. The view stays `restoring`, which no guard and no screen treats as a session.
  useEffect(() => {
    if (hasCookie) void session.boot();
  }, [session, hasCookie]);

  useEffect(() => {
    if (view.status === "anonymous") queryClient.clear();
  }, [view, queryClient]);

  if (view.status === "restore-failed") return <BootScreen status="failed" onRetry={() => void session.retryBoot()} />;
  if (view.status === "restoring" && hasCookie) return <BootScreen status="restoring" />;
  return children;
}
