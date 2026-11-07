import { createRoute, redirect, type AnyRoute } from "@tanstack/react-router";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import type { AdminKey } from "../../i18n/resources.ts";
import { useSessionView } from "./boot/use-session-view.ts";
import { AccessPlaceholder } from "./components/AccessPlaceholder.tsx";
import { ScreenLayout } from "./components/ScreenLayout.tsx";
import type { AccessSession, ArrivalNotice } from "./session/index.ts";

/** The only routes of this change (AW59). The URL never carries a destination (AW19). */
export const ACCESS_PATHS = {
  home: "/",
  signIn: "/iniciar-sesion",
  verification: "/iniciar-sesion/verificacion",
  enrollment: "/iniciar-sesion/inscripcion",
} as const;

const NOTICE_TEXT: Record<ArrivalNotice, AdminKey> = {
  "session-expired": "notice.sessionExpired",
  "second-factor-interrupted": "notice.verificationInterrupted",
  "enrollment-interrupted": "notice.enrollmentInterrupted",
  "second-factor-already-enrolled": "notice.alreadyEnrolled",
};

const trimSlash = (pathname: string) => pathname.replace(/\/+$/, "");

/**
 * The guard of the root route (design.md D5, SS31): a restricted token only lives while its screen is in
 * view, so leaving for any other route discards it. Discarding with no token held would still clear the
 * arrival notice of the view, so it is done only when one is held.
 */
export function leaveSecondFactorScreens(session: AccessSession, pathname: string): void {
  const path = trimSlash(pathname);
  if (path === ACCESS_PATHS.verification || path === ACCESS_PATHS.enrollment) return;
  if (session.hasSecondFactorToken("mfa-verify") || session.hasSecondFactorToken("mfa-enroll")) {
    session.discardSecondFactor();
  }
}

// `throw: true` makes the router throw its own redirect: it is a Response and not an Error, which the
// rule against throwing anything but an Error would refuse to see written here.
function toSignIn(): void {
  redirect({ to: ACCESS_PATHS.signIn, replace: true, throw: true });
}

function toHome(): void {
  redirect({ to: ACCESS_PATHS.home, replace: true, throw: true });
}

const isAuthenticated = (session: AccessSession) => session.getView().status === "authenticated";

function SignInContainer({ session }: { readonly session: AccessSession }) {
  const { t } = useAdminText();
  const view = useSessionView(session);
  const notice = view.status === "anonymous" && view.notice !== undefined ? t(NOTICE_TEXT[view.notice]) : undefined;

  return <AccessPlaceholder title={t("signIn.title")} notice={notice} />;
}

function SecondFactorContainer() {
  const { t } = useAdminText();
  return <AccessPlaceholder title={t("secondFactor.title")} />;
}

function EnrollmentContainer() {
  const { t } = useAdminText();
  return <AccessPlaceholder title={t("enrollment.title")} />;
}

function SignedInContainer() {
  const { t } = useAdminText();
  return (
    <ScreenLayout title={t("signedIn.title")}>
      <p className="text-text-secondary">{t("signedIn.message")}</p>
    </ScreenLayout>
  );
}

/** The routes of access, children of the root, each behind its guard. */
export function createIdentityRoutes(root: AnyRoute, session: AccessSession): AnyRoute[] {
  const getParentRoute = () => root;
  return [
    createRoute({
      getParentRoute,
      path: ACCESS_PATHS.home,
      beforeLoad: () => {
        if (!isAuthenticated(session)) toSignIn();
      },
      component: SignedInContainer,
    }),
    createRoute({
      getParentRoute,
      path: ACCESS_PATHS.signIn,
      beforeLoad: () => {
        if (isAuthenticated(session)) toHome();
      },
      component: () => <SignInContainer session={session} />,
    }),
    createRoute({
      getParentRoute,
      path: ACCESS_PATHS.verification,
      beforeLoad: () => {
        if (isAuthenticated(session)) toHome();
        if (!session.hasSecondFactorToken("mfa-verify")) {
          session.discardSecondFactor("second-factor-interrupted");
          toSignIn();
        }
      },
      component: SecondFactorContainer,
    }),
    createRoute({
      getParentRoute,
      path: ACCESS_PATHS.enrollment,
      beforeLoad: () => {
        if (isAuthenticated(session)) toHome();
        if (!session.hasSecondFactorToken("mfa-enroll")) {
          session.discardSecondFactor("enrollment-interrupted");
          toSignIn();
        }
      },
      component: EnrollmentContainer,
    }),
  ];
}
