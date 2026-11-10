import { ErrorState, Spinner } from "@confia/ui";
import { useNavigate } from "@tanstack/react-router";
import { useEffect, useState, useSyncExternalStore } from "react";
import type { AdminKey } from "../../../i18n/resources.ts";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { classifyAccessFailure, type AccessFailure } from "../../../shared/forms/access-failure.ts";
import { CopyButton } from "../components/CopyButton.tsx";
import { ScreenLayout } from "../components/ScreenLayout.tsx";
import type { AccessSession } from "../session/index.ts";
import { SecretStep } from "./components/SecretStep.tsx";
import { buildOtpauthUri } from "./otpauth-uri.ts";

export interface EnrollmentContainerProps {
  readonly session: AccessSession;
  /** Where an interrupted enrollment leads. A prop, so this file does not import the routes. */
  readonly signInPath: string;
}

/** What the person is told besides the title of the error; the same texts the forms use. */
function detailKey(failure: AccessFailure): AdminKey {
  switch (failure.kind) {
    case "message":
      return failure.key;
    case "rate-limited":
      return "problem.rateLimited";
    case "fields":
      return "problem.notProcessed";
    default:
      return "problem.unexpected";
  }
}

const traceOf = (failure: AccessFailure): string | undefined => ("traceId" in failure ? failure.traceId : undefined);

/**
 * The enrollment screen up to its secret step (design.md E5, E10). The draft lives in the session module and is read
 * with `useSyncExternalStore`; the request is started in an effect and is one promise per restricted token, so the
 * double mount of StrictMode sends one (SS75). After a failure only the person's retry sends the next request (SS87).
 * The secret, the URI and the codes stay in the draft: none is put in a query, a mutation, the URL or any storage.
 */
export function EnrollmentContainer({ session, signInPath }: EnrollmentContainerProps) {
  const { t } = useAdminText();
  const navigate = useNavigate();
  const snapshot = useSyncExternalStore(
    (listener) => session.subscribe(listener),
    () => session.getEnrollment(),
  );
  const [failure, setFailure] = useState<AccessFailure | undefined>();
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    // An answer that arrives after the effect was cleaned up belongs to a mount that is gone.
    let current = true;
    async function leave(notice: "enrollment-interrupted" | "second-factor-already-enrolled") {
      session.discardSecondFactor(notice);
      await navigate({ to: signInPath, replace: true });
    }
    void session.startEnrollment().then(async (outcome) => {
      if (!current || outcome.kind === "ready") return;
      if (outcome.kind === "no-token") return leave("enrollment-interrupted");
      if (outcome.result.kind === "problem" && outcome.result.problem.code === "mfa-already-enrolled") return leave("second-factor-already-enrolled");
      const classified = classifyAccessFailure(outcome.result);
      if (classified.kind === "restricted-token-ended") return leave("enrollment-interrupted");
      setFailure(classified);
    });
    return () => {
      current = false;
    };
  }, [session, signInPath, navigate, attempt]);

  function retry() {
    setFailure(undefined);
    setAttempt((value) => value + 1);
  }

  return (
    <ScreenLayout title={t("enrollment.title")}>
      {snapshot !== undefined ? (
        // The next steps arrive with the recovery codes and the first code.
        snapshot.step === "secret" ? (
          <SecretStep
            uri={buildOtpauthUri(snapshot.identifier, snapshot.secret)}
            secret={snapshot.secret}
            onContinue={() => {
              session.advanceEnrollment();
            }}
          />
        ) : null
      ) : failure === undefined ? (
        <div className="flex items-center gap-3">
          <Spinner />
          <p role="status" aria-live="polite" className="text-text-secondary">
            {t("enrollment.loading")}
          </p>
        </div>
      ) : (
        <>
          <ErrorState title={t("enrollment.failed")} description={t(detailKey(failure))} onRetry={retry} />
          {traceOf(failure) === undefined ? null : (
            <p className="flex flex-wrap items-center justify-center gap-2">
              <code className="break-all">{traceOf(failure)}</code>
              <CopyButton text={traceOf(failure) ?? ""} kind="trace" />
            </p>
          )}
        </>
      )}
    </ScreenLayout>
  );
}
