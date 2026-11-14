import { useMutation, useQuery } from "@tanstack/react-query";
import { useRef } from "react";
import type { AdminKey } from "../../../i18n/resources.ts";
import { ApiFailure, type ApiFailureResult } from "../../../shared/api/api-failure.ts";
import { PROBLEM_KEYS } from "../../../shared/api/problem-keys.ts";
import { useSessionView } from "../boot/use-session-view.ts";
import { currentSessionQueryOptions } from "../session/current-session-query.ts";
import type { AccessSession } from "../session/index.ts";

function failureKey(error: unknown): AdminKey {
  const result: ApiFailureResult | undefined = error instanceof ApiFailure ? error.result : undefined;
  if (result?.kind === "network") return "problem.network";
  if (result?.kind === "problem") return PROBLEM_KEYS[result.problem.code];
  return "problem.unexpected";
}

export type SignedInSession =
  | { readonly authenticated: false }
  | { readonly authenticated: true; readonly read: "loading" }
  | { readonly authenticated: true; readonly read: "error"; readonly errorKey: AdminKey; readonly retry: () => void }
  | {
      readonly authenticated: true;
      readonly read: "ready";
      readonly signingOut: boolean;
      readonly signOutFailed: boolean;
      readonly signOut: () => void;
    };

/**
 * What the signed in screens need of the session: whether it is still alive, the read of the current
 * session (which keeps metadata only in the query cache and shows none of it) and the sign out. Every
 * road that ends the session (a 401, a failed refresh, the sign out) changes the view, and the view
 * sends the person to sign in. `useMutation` is used here and only here: the sign out has no variables
 * and no data for the mutation cache to keep (design.md D9).
 */
export function useSignedInSession(session: AccessSession): SignedInSession {
  const view = useSessionView(session);
  const read = useQuery(currentSessionQueryOptions(session));
  // A ref, not the state of the mutation: two clicks in the same tick see the same render.
  const sending = useRef(false);
  const signOut = useMutation({
    mutationFn: async () => {
      const outcome = await session.signOut();
      if (outcome.kind === "failed") throw new Error("sign out failed");
    },
    onSettled: () => {
      sending.current = false;
    },
  });

  if (view.status !== "authenticated") return { authenticated: false };
  if (read.isError) {
    return {
      authenticated: true,
      read: "error",
      errorKey: failureKey(read.error),
      retry: () => void read.refetch(),
    };
  }
  if (!read.isSuccess) return { authenticated: true, read: "loading" };

  return {
    authenticated: true,
    read: "ready",
    signingOut: signOut.isPending,
    signOutFailed: signOut.isError,
    signOut: () => {
      if (sending.current) return;
      sending.current = true;
      signOut.mutate();
    },
  };
}
