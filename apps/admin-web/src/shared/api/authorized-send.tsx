import { createContext, useContext, type ReactNode } from "react";
import type { ApiResult } from "./http.ts";

/**
 * How a screen sends an authenticated request without ever holding a token: it hands over an operation
 * that needs one, and gets the data back or an `ApiFailure`. The app provides the real one from the
 * session; shared/ does not know the session, so this is a port (the rule admin-shared-never-imports-features).
 */
export type AuthorizedSend = <T>(operation: (accessToken: string) => Promise<ApiResult<T>>) => Promise<T>;

const AuthorizedSendContext = createContext<AuthorizedSend | undefined>(undefined);

export interface AuthorizedSendProviderProps {
  readonly send: AuthorizedSend;
  readonly children: ReactNode;
}

export function AuthorizedSendProvider({ send, children }: AuthorizedSendProviderProps) {
  return <AuthorizedSendContext value={send}>{children}</AuthorizedSendContext>;
}

/** The sender of the signed in shell. A screen rendered outside it is a defect, so this throws. */
export function useAuthorizedSend(): AuthorizedSend {
  const send = useContext(AuthorizedSendContext);
  if (send === undefined) throw new Error("useAuthorizedSend needs an AuthorizedSendProvider above it.");
  return send;
}
