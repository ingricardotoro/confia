import { useSyncExternalStore } from "react";
import type { AccessSession, SessionView } from "../session/index.ts";

/** The view of the session for a component: it re-renders when the view changes, and never sees a token. */
export function useSessionView(session: Pick<AccessSession, "subscribe" | "getView">): SessionView {
  return useSyncExternalStore(session.subscribe, session.getView);
}
