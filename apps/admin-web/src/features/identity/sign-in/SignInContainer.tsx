import { useNavigate } from "@tanstack/react-router";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import type { AdminKey } from "../../../i18n/resources.ts";
import { classifyAccessFailure } from "../../../shared/forms/access-failure.ts";
import { useAccessForm } from "../../../shared/forms/use-access-form.ts";
import { useSessionView } from "../boot/use-session-view.ts";
import type { AccessSession, ArrivalNotice } from "../session/index.ts";
import { SignInForm } from "./components/SignInForm.tsx";
import { normalizeIdentifier, SIGN_IN_CONTROL_IDS, validateSignIn, type SignInField } from "./sign-in-schema.ts";

const NOTICE_TEXT: Record<ArrivalNotice, AdminKey> = {
  "session-expired": "notice.sessionExpired",
  "second-factor-interrupted": "notice.verificationInterrupted",
  "enrollment-interrupted": "notice.enrollmentInterrupted",
  "second-factor-already-enrolled": "notice.alreadyEnrolled",
};

export interface SignInContainerProps {
  readonly session: AccessSession;
  /** Where each answer of the server leads. Props, so this file does not import the routes. */
  readonly homePath: string;
  readonly verificationPath: string;
  readonly enrollmentPath: string;
}

/**
 * The sign in screen (design.md E1, E3, E5, E8). It sends the credentials through the session and decides what is
 * its own, which is where each answer leads; the shared hook does the rest. The password lives in the form only: no
 * query or mutation holds it, so nothing of it reaches a cache (SS03). The answers that need a second factor stack an
 * entry, so going back is possible (SS31); only arriving at the signed in page replaces one (SS101).
 */
export function SignInContainer({ session, homePath, verificationPath, enrollmentPath }: SignInContainerProps) {
  const { t } = useAdminText();
  const navigate = useNavigate();
  const view = useSessionView(session);
  const notice = view.status === "anonymous" && view.notice !== undefined ? t(NOTICE_TEXT[view.notice]) : undefined;

  const form = useAccessForm<SignInField>({
    fields: {
      identifier: { controlId: SIGN_IN_CONTROL_IDS.identifier, normalize: normalizeIdentifier },
      password: { controlId: SIGN_IN_CONTROL_IDS.password },
    },
    validate: validateSignIn,
    waitingKey: "signIn.waiting",
    async submit(credentials) {
      const outcome = await session.signIn(credentials);
      switch (outcome.kind) {
        case "failed":
          return classifyAccessFailure(outcome.result);
        case "authenticated":
          await navigate({ to: homePath, replace: true });
          return undefined;
        case "second-factor-required":
          await navigate({ to: verificationPath });
          return undefined;
        case "enrollment-required":
          await navigate({ to: enrollmentPath });
          return undefined;
      }
    },
  });

  return <SignInForm {...form} notice={notice} />;
}
