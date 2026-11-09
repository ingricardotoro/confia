import { useForm, useSelector } from "@tanstack/react-form";
import { useEffect, useRef, useState, type Ref, type SyntheticEvent } from "react";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import type { AdminKey } from "../../i18n/resources.ts";
import type { AccessFailure } from "./access-failure.ts";
import { resolveFieldErrors, type FieldIssue } from "./field-errors.ts";
import { useRetryCountdown } from "./use-retry-countdown.ts";

/** What a form's own validation says about a field. Reasons, not texts: the hook turns them into catalog keys. */
export type FieldReason = "required" | "too-long" | "format";

export interface AccessFieldSpec {
  /** The fixed `id` of the control, which the error summary links to. */
  readonly controlId: string;
  readonly initialValue?: string;
  /** Applied before validating and before sending, so the validated, the sent and the kept value are the same. */
  readonly normalize?: (raw: string) => string;
}

export interface UseAccessFormOptions<F extends string> {
  readonly fields: Readonly<Record<F, AccessFieldSpec>>;
  /** Receives the normalized values. */
  readonly validate: (values: Readonly<Record<F, string>>) => Partial<Record<F, FieldReason>>;
  /** Receives the normalized values and returns the failure, if any. It must not throw. */
  readonly submit: (values: Readonly<Record<F, string>>) => Promise<AccessFailure | undefined>;
  /** The text of the polite region while a request is in flight. */
  readonly waitingKey: AdminKey;
}

/** What a presentation sees of an access form (design.md section 6). */
export interface AccessFormView<F extends string> {
  readonly values: Readonly<Record<F, string>>;
  /** Already translated. */
  readonly fieldErrors: Readonly<Partial<Record<F, string>>>;
  /** For `FormErrorSummary`; empty until a submit fails. */
  readonly summary: readonly { readonly controlId: string; readonly message: string }[];
  readonly alert?: { readonly message: string; readonly traceId?: string };
  /** The wait after a 429, translated. It goes outside every active region: a number that changes each second must not be announced. */
  readonly countdown?: { readonly remaining: string };
  /** The text of the form's one polite region: the wait while a request is in flight, the end of a 429 wait, or nothing. */
  readonly liveMessage: string;
  readonly submitting: boolean;
  readonly summaryRef: Ref<HTMLDivElement>;
  readonly alertRef: Ref<HTMLDivElement>;
  readonly onChange: (field: F, value: string) => void;
  readonly onBlur: (field: F) => void;
  readonly onSubmit: (event: SyntheticEvent) => void;
}

type FocusRequest = { readonly count: number; readonly target: "summary" | "alert" };

/**
 * The behavior every access form shares (design.md E3): validate before sending and on leaving a field, never
 * send twice at once, keep what was typed after a failure, and move the focus to the summary or to the alert.
 * It is the only file of the app that imports the form library.
 */
export function useAccessForm<F extends string>({ fields, validate, submit, waitingKey }: UseAccessFormOptions<F>): AccessFormView<F> {
  const { t } = useAdminText();
  const names = Object.keys(fields) as F[];
  const form = useForm({
    defaultValues: Object.fromEntries(names.map((name) => [name, fields[name].initialValue ?? ""])) as Record<F, string>,
  });
  const values = useSelector(form.store, (state) => state.values);

  const [issues, setIssues] = useState<readonly FieldIssue[]>([]);
  const [summaryShown, setSummaryShown] = useState(false);
  const [alert, setAlert] = useState<AccessFormView<F>["alert"]>();
  const [submitting, setSubmitting] = useState(false);
  const [focusRequest, setFocusRequest] = useState<FocusRequest>({ count: 0, target: "alert" });
  const inFlight = useRef(false);
  const countdown = useRetryCountdown();
  // Callback refs kept in state, so the effect below sees an element that mounts in the same commit as the request.
  const [summaryElement, setSummaryElement] = useState<HTMLDivElement | null>(null);
  const [alertElement, setAlertElement] = useState<HTMLDivElement | null>(null);
  const focused = useRef(0);

  // The focus moves in an effect, after the summary or the alert is in the document, once per failed submit.
  useEffect(() => {
    if (focusRequest.count === focused.current) return;
    const target = focusRequest.target === "summary" ? summaryElement : alertElement;
    if (target === null) return;
    focused.current = focusRequest.count;
    target.focus();
  }, [focusRequest, summaryElement, alertElement]);

  const controlIds = Object.fromEntries(names.map((name) => [name, fields[name].controlId])) as Record<F, string>;

  function normalized(raw: Readonly<Record<F, string>>): Record<F, string> {
    return Object.fromEntries(names.map((name) => [name, (fields[name].normalize ?? ((value: string) => value))(raw[name])])) as Record<F, string>;
  }

  function issuesOf(found: Partial<Record<F, FieldReason>>): FieldIssue[] {
    return names.flatMap((name) => {
      const reason = found[name];
      return reason === undefined ? [] : [{ field: name, reason }];
    });
  }

  function fail(next: readonly FieldIssue[], target: FocusRequest["target"]) {
    setAlert(undefined);
    setIssues(next);
    setSummaryShown(next.length > 0);
    setFocusRequest((previous) => ({ count: previous.count + 1, target }));
  }

  // One field changes its own error and leaves the others as they were.
  function recheck(field: F, raw: Readonly<Record<F, string>>) {
    const reason = validate(normalized(raw))[field];
    setIssues((previous) => [
      ...previous.filter((issue) => issue.field !== field),
      ...(reason === undefined ? [] : [{ field, reason }]),
    ]);
  }

  async function send(sent: Readonly<Record<F, string>>) {
    inFlight.current = true;
    setSubmitting(true);
    setAlert(undefined);
    setIssues([]);
    setSummaryShown(false);
    countdown.clear();
    let failure: AccessFailure | undefined;
    try {
      failure = await submit(sent);
    } finally {
      inFlight.current = false;
      setSubmitting(false);
    }
    if (failure === undefined) return;
    if (failure.kind === "fields") {
      fail(failure.errors, "summary");
      return;
    }
    if (failure.kind === "rate-limited") countdown.start(failure.retryAfterSeconds);
    const traceId = "traceId" in failure ? failure.traceId : undefined;
    const key = failure.kind === "message" ? failure.key : failure.kind === "rate-limited" ? "problem.rateLimited" : "problem.unexpected";
    setAlert({ message: t(key), ...(traceId === undefined ? {} : { traceId }) });
    setFocusRequest((previous) => ({ count: previous.count + 1, target: "alert" }));
  }

  function onSubmit(event: SyntheticEvent) {
    event.preventDefault();
    // The button already refuses a second activation while it is busy; this guard covers the submit that does not
    // go through it, such as Enter in a field, and the one that arrives before the render that marks it busy.
    if (inFlight.current) return;
    // During a 429 wait nothing is sent and the count goes on; the person is taken back to the reason.
    if (countdown.isActive()) {
      alertElement?.focus();
      return;
    }
    const sent = normalized(form.state.values);
    const found = issuesOf(validate(sent));
    if (found.length > 0) {
      fail(found, "summary");
      return;
    }
    void send(sent);
  }

  const { byField, summary } = resolveFieldErrors(issues, controlIds);
  const fieldErrors = Object.fromEntries(Object.entries(byField).map(([name, key]) => [name, t(key as AdminKey)])) as Partial<Record<F, string>>;

  return {
    values,
    fieldErrors,
    summary: summaryShown ? summary.map(({ controlId, key }) => ({ controlId, message: t(key) })) : [],
    ...(alert === undefined ? {} : { alert }),
    ...(countdown.remainingSeconds === undefined ? {} : { countdown: { remaining: t("countdown.remaining", { seconds: countdown.remainingSeconds }) } }),
    liveMessage: submitting ? t(waitingKey) : countdown.finished ? t("countdown.done") : "",
    submitting,
    summaryRef: setSummaryElement,
    alertRef: setAlertElement,
    onChange(field, value) {
      form.setFieldValue(field as never, value as never);
      if (issues.some((issue) => issue.field === field)) recheck(field, { ...form.state.values, [field]: value });
    },
    onBlur(field) {
      recheck(field, form.state.values);
    },
    onSubmit,
  };
}
