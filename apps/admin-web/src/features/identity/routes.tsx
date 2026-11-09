import { createRoute, redirect, type AnyRoute } from "@tanstack/react-router";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { AccessPlaceholder } from "./components/AccessPlaceholder.tsx";
import { SecondFactorContainer } from "./second-factor/SecondFactorContainer.tsx";
import { SignedInContainer } from "./signed-in/SignedInContainer.tsx";
import { SignInContainer } from "./sign-in/SignInContainer.tsx";
import type { AccessSession } from "./session/index.ts";

/** The only routes of this change (AW59). The URL never carries a destination (AW19). */
export const ACCESS_PATHS = {
  home: "/",
  signIn: "/iniciar-sesion",
  verification: "/iniciar-sesion/verificacion",
  enrollment: "/iniciar-sesion/inscripcion",
} as const;

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

function EnrollmentContainer() {
  const { t } = useAdminText();
  return <AccessPlaceholder title={t("enrollment.title")} />;
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
      component: () => <SignedInContainer session={session} signInPath={ACCESS_PATHS.signIn} />,
    }),
    createRoute({
      getParentRoute,
      path: ACCESS_PATHS.signIn,
      beforeLoad: () => {
        if (isAuthenticated(session)) toHome();
      },
      component: () => (
        <SignInContainer
          session={session}
          homePath={ACCESS_PATHS.home}
          verificationPath={ACCESS_PATHS.verification}
          enrollmentPath={ACCESS_PATHS.enrollment}
        />
      ),
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
      component: () => <SecondFactorContainer session={session} homePath={ACCESS_PATHS.home} signInPath={ACCESS_PATHS.signIn} />,
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
