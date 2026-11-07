import { useMutation, useQuery } from "@tanstack/react-query";
import { Navigate } from "@tanstack/react-router";
import { useRef } from "react";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { ApiFailure, type ApiFailureResult } from "../../../shared/api/api-failure.ts";
import { PROBLEM_KEYS } from "../../../shared/api/problem-keys.ts";
import type { AdminKey } from "../../../i18n/resources.ts";
import { useSessionView } from "../boot/use-session-view.ts";
import { currentSessionQueryOptions } from "../session/current-session-query.ts";
import type { AccessSession } from "../session/index.ts";
import { SignedInPage } from "./components/SignedInPage.tsx";

export interface SignedInContainerProps {
  readonly session: AccessSession;
  /** Where the person goes when the session is over, by any road. A prop, so this file does not import the routes. */
  readonly signInPath: string;
}

function failureKey(error: unknown): AdminKey {
  const result: ApiFailureResult | undefined = error instanceof ApiFailure ? error.result : undefined;
  if (result?.kind === "network") return "problem.network";
  if (result?.kind === "problem") return PROBLEM_KEYS[result.problem.code];
  return "problem.unexpected";
}

/**
 * The signed in page: it reads the current session, which keeps metadata only in the query cache and shows
 * none of it, and it signs out. Every road that ends the session (a 401, a failed refresh, the sign out)
 * changes the view, and the view sends the person to sign in. `useMutation` is used here and only here:
 * the sign out has no variables and no data for the mutation cache to keep (design.md D9).
 */
export function SignedInContainer({ session, signInPath }: SignedInContainerProps) {
  const { t } = useAdminText();
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

  if (view.status !== "authenticated") return <Navigate to={signInPath} replace />;
  if (read.isError) return <SignedInPage status="error" message={t(failureKey(read.error))} onRetry={() => void read.refetch()} />;
  if (!read.isSuccess) return <SignedInPage status="loading" />;

  return (
    <SignedInPage
      status="ready"
      signingOut={signOut.isPending}
      signOutFailed={signOut.isError}
      onSignOut={() => {
        if (sending.current) return;
        sending.current = true;
        signOut.mutate();
      }}
    />
  );
}
