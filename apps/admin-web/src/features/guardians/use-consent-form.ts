import type { ConsentChangeResponseOutput, RecordConsentRequest } from "@confia/contracts/admin";
import { useEffect, useRef, useState, type Ref, type SyntheticEvent } from "react";
import type { AdminKey } from "../../i18n/resources.ts";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import { resolveFieldErrors } from "../../shared/forms/field-errors.ts";
import { useRetryCountdown } from "../../shared/forms/use-retry-countdown.ts";
import { failureOf } from "../organization/organization-failure.ts";
import {
  CONSENT_CONTROL_IDS,
  EMPTY_CONSENT_DRAFT,
  consentActionId,
  consentBody,
  validateConsent,
  type ConsentDraft,
  type ConsentField,
  type ConsentTarget,
} from "./consent-model.ts";

type Issues = Partial<Record<ConsentField, string>>;

type FocusTarget = "summary" | "alert" | { readonly id: string };

export interface ConsentFormView {
  readonly target: ConsentTarget;
  readonly draft: ConsentDraft;
  /** Already translated, by field. */
  readonly errors: Issues;
  readonly summary: readonly {
    readonly controlId: string;
    readonly message: string;
  }[];
  readonly alert?: { readonly message: string; readonly traceId?: string };
  readonly countdown?: { readonly remaining: string };
  readonly submitting: boolean;
  readonly liveMessage: string;
  readonly attachSummary: Ref<HTMLDivElement>;
  readonly attachAlert: Ref<HTMLDivElement>;
  readonly change: (patch: Partial<ConsentDraft>) => void;
  readonly submit: (event: SyntheticEvent) => void;
  readonly cancel: () => void;
}

export interface ConsentFormHook {
  readonly form: ConsentFormView | undefined;
  /** The text of the last change, already translated; empty until there is one. */
  readonly message: string;
  readonly open: (target: ConsentTarget) => void;
}

export interface UseConsentFormOptions {
  readonly record: (body: RecordConsentRequest) => Promise<ConsentChangeResponseOutput>;
  readonly channelLabel: (channel: ConsentTarget["channel"]) => string;
  readonly purposeLabel: (purpose: ConsentTarget["purpose"]) => string;
}

/**
 * The form that records one grant or one withdrawal. Nothing is preselected: the evidence is chosen by the person and the
 * policy version is typed. The server decides every rule; here only the shape is checked before sending. One send at a time
 * (a second submit while one is in flight sends nothing), the body is not kept after it settles, and the person is told
 * whether the server wrote an event (`changed`) or the request repeated the current state.
 */
export function useConsentForm({ record, channelLabel, purposeLabel }: UseConsentFormOptions): ConsentFormHook {
  const { t } = useAdminText();
  const [target, setTarget] = useState<ConsentTarget>();
  const [draft, setDraft] = useState<ConsentDraft>(EMPTY_CONSENT_DRAFT);
  const [issues, setIssues] = useState<Issues>({});
  const [summaryShown, setSummaryShown] = useState(false);
  const [alert, setAlert] = useState<{ message: string; traceId?: string }>();
  const [submitting, setSubmitting] = useState(false);
  const [message, setMessage] = useState("");
  const [focusRequest, setFocusRequest] = useState<{
    count: number;
    target: FocusTarget;
  }>({ count: 0, target: "alert" });
  const [summaryElement, setSummaryElement] = useState<HTMLDivElement | null>(null);
  const [alertElement, setAlertElement] = useState<HTMLDivElement | null>(null);
  const focused = useRef(0);
  const inFlight = useRef(false);
  const countdown = useRetryCountdown();

  useEffect(() => {
    if (focusRequest.count === focused.current) return;
    const element =
      focusRequest.target === "summary"
        ? summaryElement
        : focusRequest.target === "alert"
          ? alertElement
          : document.getElementById(focusRequest.target.id);
    if (element === null) return;
    focused.current = focusRequest.count;
    element.focus();
  }, [focusRequest, summaryElement, alertElement]);

  const askFocus = (next: FocusTarget) => {
    setFocusRequest((previous) => ({
      count: previous.count + 1,
      target: next,
    }));
  };

  function reset() {
    setIssues({});
    setSummaryShown(false);
    setAlert(undefined);
    countdown.clear();
  }

  function fail(found: readonly { field: string; reason: string }[]) {
    const { byField, summary } = resolveFieldErrors(found, CONSENT_CONTROL_IDS);
    setAlert(undefined);
    setIssues(Object.fromEntries(Object.entries(byField).map(([field, key]) => [field, t(key)])));
    setSummaryShown(summary.length > 0);
    askFocus("summary");
  }

  async function send(current: ConsentTarget, sent: ConsentDraft) {
    inFlight.current = true;
    setSubmitting(true);
    reset();
    try {
      const result = await record(consentBody(current, sent));
      const names = {
        channel: channelLabel(result.channel),
        purpose: purposeLabel(result.purpose),
      };
      const key: AdminKey =
        current.action === "GRANT"
          ? result.changed
            ? "guardians.consents.result.grantChanged"
            : "guardians.consents.result.grantUnchanged"
          : result.changed
            ? "guardians.consents.result.withdrawChanged"
            : "guardians.consents.result.withdrawUnchanged";
      setMessage(t(key, names));
      setTarget(undefined);
      setDraft(EMPTY_CONSENT_DRAFT);
      askFocus({ id: consentActionId(current) });
    } catch (error) {
      const failure = failureOf(error);
      if (failure.kind === "fields") {
        if (failure.errors.some((entry) => Object.hasOwn(CONSENT_CONTROL_IDS, entry.field))) fail(failure.errors);
        else {
          setAlert({ message: t("problem.notProcessed") });
          askFocus("alert");
        }
      } else {
        if (failure.kind === "rate-limited") countdown.start(failure.retryAfterSeconds);
        const traceId = "traceId" in failure ? failure.traceId : undefined;
        // The shared text of an inactive guardian speaks of links; here it must say that a withdrawal is still possible.
        const key: AdminKey =
          failure.kind === "message"
            ? failure.key === "problem.guardianNotActive"
              ? "problem.guardianNotActiveForConsent"
              : failure.key
            : failure.kind === "rate-limited"
              ? "problem.rateLimited"
              : "problem.unexpected";
        setAlert({
          message: t(key),
          ...(traceId === undefined ? {} : { traceId }),
        });
        askFocus("alert");
      }
    } finally {
      inFlight.current = false;
      setSubmitting(false);
    }
  }

  function change(patch: Partial<ConsentDraft>) {
    setDraft((previous) => ({ ...previous, ...patch }));
  }

  function submit(event: SyntheticEvent) {
    event.preventDefault();
    if (target === undefined || inFlight.current) return;
    if (countdown.isActive()) {
      alertElement?.focus();
      return;
    }
    const found = validateConsent(target, draft);
    if (Object.keys(found).length > 0) {
      fail(Object.entries(found).map(([field, reason]) => ({ field, reason })));
      return;
    }
    void send(target, draft);
  }

  function cancel() {
    if (target === undefined || inFlight.current) return;
    reset();
    setTarget(undefined);
    setDraft(EMPTY_CONSENT_DRAFT);
    askFocus({ id: consentActionId(target) });
  }

  const form: ConsentFormView | undefined =
    target === undefined
      ? undefined
      : {
          target,
          draft,
          errors: issues,
          summary: summaryShown
            ? (Object.entries(issues) as [ConsentField, string][]).map(([field, text]) => ({
                controlId: CONSENT_CONTROL_IDS[field],
                message: text,
              }))
            : [],
          ...(alert === undefined ? {} : { alert }),
          ...(countdown.remainingSeconds === undefined
            ? {}
            : {
                countdown: {
                  remaining: t("countdown.remaining", {
                    seconds: countdown.remainingSeconds,
                  }),
                },
              }),
          submitting,
          liveMessage: submitting ? t("guardians.consents.form.saving") : countdown.finished ? t("countdown.done") : "",
          attachSummary: setSummaryElement,
          attachAlert: setAlertElement,
          change,
          submit,
          cancel,
        };

  function open(next: ConsentTarget) {
    if (inFlight.current) return;
    reset();
    setMessage("");
    setDraft(EMPTY_CONSENT_DRAFT);
    setTarget(next);
    askFocus({ id: next.action === "GRANT" ? CONSENT_CONTROL_IDS.policyVersion : CONSENT_CONTROL_IDS.evidenceSource });
  }

  return { form, message, open };
}
