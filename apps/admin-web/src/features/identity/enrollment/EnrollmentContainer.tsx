import { ErrorState, Spinner } from "@confia/ui";
import { useNavigate, useRouter } from "@tanstack/react-router";
import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import type { AdminKey } from "../../../i18n/resources.ts";
import { useAdminText } from "../../../i18n/use-admin-text.ts";
import { classifyAccessFailure, type AccessFailure } from "../../../shared/forms/access-failure.ts";
import { useAccessForm } from "../../../shared/forms/use-access-form.ts";
import { CopyButton } from "../components/CopyButton.tsx";
import { ScreenLayout } from "../components/ScreenLayout.tsx";
import { normalizeCode, validateSecondFactor } from "../second-factor/second-factor-schema.ts";
import type { AccessSession } from "../session/index.ts";
import { FirstCodeStep, type FirstCodeField } from "./components/FirstCodeStep.tsx";
import { RecoveryCodesStep } from "./components/RecoveryCodesStep.tsx";
import { SecretStep } from "./components/SecretStep.tsx";
import { ENROLLMENT_CONTROL_IDS } from "./enrollment-controls.ts";
import { buildOtpauthUri } from "./otpauth-uri.ts";

export interface EnrollmentContainerProps {
  readonly session: AccessSession;
  /** Where an interrupted enrollment and a finished one lead. Props, so this file does not import the routes. */
  readonly signInPath: string;
  readonly homePath: string;
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

interface StepProps {
  readonly session: AccessSession;
}

/**
 * The recovery codes section. The confirmation and the failed Continue are local to it: nothing about the codes leaves
 * the draft. Continue is never disabled; without the box it shows the summary and moves the focus to it (SS90), and with
 * it the draft advances, which forgets the codes (SS91, SS93).
 */
function RecoveryCodesEntry({ session, codes }: StepProps & { readonly codes: readonly string[] }) {
  const { t } = useAdminText();
  const [confirmed, setConfirmed] = useState(false);
  const [failedPresses, setFailedPresses] = useState(0);
  const summary = useRef<HTMLDivElement | null>(null);

  // After the summary is in the document, once per press without the box.
  useEffect(() => {
    if (failedPresses > 0) summary.current?.focus();
  }, [failedPresses]);

  const message = t("enrollment.codes.confirmRequired");
  const failed = failedPresses > 0 && !confirmed;

  return (
    <RecoveryCodesStep
      codes={codes}
      confirmed={confirmed}
      summary={failed ? [{ controlId: ENROLLMENT_CONTROL_IDS.confirmCodes, message }] : []}
      {...(failed ? { error: message } : {})}
      summaryRef={summary}
      onConfirmedChange={setConfirmed}
      onContinue={() => {
        if (!confirmed) {
          setFailedPresses((count) => count + 1);
          return;
        }
        session.advanceEnrollment();
      }}
    />
  );
}

interface FirstCodeEntryProps extends StepProps {
  readonly uri: string;
  readonly secret: string;
  readonly signInPath: string;
  readonly homePath: string;
}

/**
 * The first code section. The code is sent through the session as a TOTP and nothing else (SS96); it never reaches a query
 * or a mutation, so no cache holds it (SS03). A `401 second-factor-invalid` keeps the step, with the QR code and the key in view (SS97).
 */
function FirstCodeEntry({ session, uri, secret, signInPath, homePath }: FirstCodeEntryProps) {
  const navigate = useNavigate();
  const router = useRouter();
  // The activation forgets the draft, which unmounts this section before its own answer arrives, so being mounted says nothing
  // about whether the person is still here. The place is what says it: an answer that arrives after they left navigates nobody.
  const entered = useRef(router.state.location.pathname);
  const stillHere = () => router.state.location.pathname === entered.current;

  const form = useAccessForm<FirstCodeField>({
    fields: { code: { controlId: ENROLLMENT_CONTROL_IDS.firstCode, normalize: normalizeCode } },
    validate: (values) => validateSecondFactor("code", { code: values.code, recoveryCode: "" }),
    waitingKey: "secondFactor.waiting",
    async submit(values): Promise<AccessFailure | undefined> {
      const outcome = await session.activate(values.code);
      if (outcome.kind === "authenticated") {
        if (stillHere()) await navigate({ to: homePath, replace: true });
        return undefined;
      }
      // The session already ended the enrollment and left the notice: the sign in tells the person why.
      const failure = outcome.kind === "no-token" ? undefined : classifyAccessFailure(outcome.result);
      if (failure === undefined || failure.kind === "restricted-token-ended") {
        if (stillHere()) await navigate({ to: signInPath, replace: true });
        return undefined;
      }
      return failure;
    },
  });

  return (
    <FirstCodeStep
      {...form}
      uri={uri}
      secret={secret}
      onReviewSecret={() => {
        if (!form.submitting) session.reviewEnrollmentSecret();
      }}
    />
  );
}

/**
 * The enrollment screen (design.md E5, E10). The draft lives in the session module and is read
 * with `useSyncExternalStore`; the request is started in an effect and is one promise per restricted token, so the
 * double mount of StrictMode sends one (SS75). After a failure only the person's retry sends the next request (SS87).
 * The secret, the URI and the codes stay in the draft: none is put in a query, a mutation, the URL or any storage.
 */
export function EnrollmentContainer({ session, signInPath, homePath }: EnrollmentContainerProps) {
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
        snapshot.step === "secret" ? (
          <SecretStep
            uri={buildOtpauthUri(snapshot.identifier, snapshot.secret)}
            secret={snapshot.secret}
            onContinue={() => {
              session.advanceEnrollment();
            }}
          />
        ) : snapshot.step === "recovery-codes" ? (
          <RecoveryCodesEntry session={session} codes={snapshot.recoveryCodes ?? []} />
        ) : (
          <FirstCodeEntry
            session={session}
            uri={buildOtpauthUri(snapshot.identifier, snapshot.secret)}
            secret={snapshot.secret}
            signInPath={signInPath}
            homePath={homePath}
          />
        )
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
