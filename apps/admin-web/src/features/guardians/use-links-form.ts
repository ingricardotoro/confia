import type { ReplaceStudentGuardiansRequest } from "@confia/contracts/admin";
import { useEffect, useRef, useState, type Ref, type SyntheticEvent } from "react";
import type { AdminKey } from "../../i18n/resources.ts";
import { useAdminText } from "../../i18n/use-admin-text.ts";
import type { AccessFailure } from "../../shared/forms/access-failure.ts";
import { fieldErrorKey } from "../../shared/forms/field-errors.ts";
import { useRetryCountdown } from "../../shared/forms/use-retry-countdown.ts";
import { failureOf } from "../organization/organization-failure.ts";
import {
  MAX_LINKS,
  bodyOf,
  choiceNameOf,
  draftOf,
  linkControlId,
  linkRemoveId,
  validateLinks,
  type GuardianChoice,
  type LinkDraft,
  type LinkField,
} from "./link-model.ts";

/** The id of the box that adds a guardian: the focus goes back to it when a row is removed. */
export const ADD_GUARDIAN_CONTROL_ID = "link-guardian-search";

type Issues = Readonly<Record<string, Partial<Record<LinkField, string>>>>;

export interface LinksFormView {
  readonly rows: readonly LinkDraft[];
  /** Already translated, by row key. */
  readonly rowErrors: Readonly<Record<string, Partial<Record<LinkField, string>>>>;
  readonly summary: readonly { readonly controlId: string; readonly message: string }[];
  readonly alert?: { readonly message: string; readonly traceId?: string };
  readonly countdown?: { readonly remaining: string };
  /** The one polite region of the form: the wait, the end of a 429 wait, or what the last add or remove did. */
  readonly liveMessage: string;
  readonly submitting: boolean;
  readonly full: boolean;
  readonly attachSummary: Ref<HTMLDivElement>;
  readonly attachAlert: Ref<HTMLDivElement>;
  readonly add: (guardian: GuardianChoice) => void;
  readonly remove: (key: string) => void;
  readonly change: (key: string, patch: Partial<Pick<LinkDraft, "relationship" | "percent" | "primary" | "billing">>) => void;
  readonly onSubmit: (event: SyntheticEvent) => void;
}

export interface UseLinksFormOptions {
  /** The rows the server holds; empty while they load. Stable between renders. */
  readonly initial: readonly LinkDraft[];
  /** Rejects with the failure of the request; resolves when the set is saved. */
  readonly save: (body: ReplaceStudentGuardiansRequest) => Promise<void>;
}

const SERVER_FIELD = /^links\[(\d+)\]\.(relationship|financialResponsibilityPercent)$/;

type FocusRequest = { readonly count: number; readonly target: "summary" | "alert" | { readonly id: string } };

/**
 * The behavior of the form that replaces the guardians of a student, which is a list and not a fixed set of fields: add and
 * remove rows, validate each row before sending, send once, keep what was typed after a failure and move the focus (to the
 * summary, to the alert, to the new row or back to the search box). The rows hold the typed percentages as text. Nothing
 * here adds them up: the sum is the server's.
 */
export function useLinksForm({ initial, save }: UseLinksFormOptions): LinksFormView {
  const { t } = useAdminText();
  // The rows are the set the server holds until the person touches one; from then on they are the person's. `initial` may
  // arrive after the dialog opened (it loads), and it must be stable between renders.
  const [edited, setRows] = useState<readonly LinkDraft[]>();
  const rows = edited ?? initial;
  const [issues, setIssues] = useState<Issues>({});
  const [summaryShown, setSummaryShown] = useState(false);
  const [alert, setAlert] = useState<LinksFormView["alert"]>();
  const [announcement, setAnnouncement] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [focusRequest, setFocusRequest] = useState<FocusRequest>({ count: 0, target: "alert" });
  const inFlight = useRef(false);
  const countdown = useRetryCountdown();
  const [summaryElement, setSummaryElement] = useState<HTMLDivElement | null>(null);
  const [alertElement, setAlertElement] = useState<HTMLDivElement | null>(null);
  const focused = useRef(0);

  useEffect(() => {
    if (focusRequest.count === focused.current) return;
    const target =
      focusRequest.target === "summary" ? summaryElement : focusRequest.target === "alert" ? alertElement : document.getElementById(focusRequest.target.id);
    if (target === null) return;
    focused.current = focusRequest.count;
    target.focus();
  }, [focusRequest, summaryElement, alertElement]);

  const askFocus = (target: FocusRequest["target"]) => {
    setFocusRequest((previous) => ({ count: previous.count + 1, target }));
  };

  const translate = (reasons: Partial<Record<LinkField, string>>): Partial<Record<LinkField, string>> =>
    Object.fromEntries(Object.entries(reasons).map(([field, reason]) => [field, t(fieldErrorKey(reason))]));

  function issuesOf(found: Record<string, Partial<Record<LinkField, string>>>): Issues {
    return Object.fromEntries(Object.entries(found).map(([key, reasons]) => [key, translate(reasons)]));
  }

  function fail(next: Issues) {
    setAlert(undefined);
    setIssues(next);
    setSummaryShown(Object.keys(next).length > 0);
    askFocus("summary");
  }

  async function send(sent: readonly LinkDraft[]) {
    inFlight.current = true;
    setSubmitting(true);
    setAlert(undefined);
    setIssues({});
    setSummaryShown(false);
    countdown.clear();
    let failure: AccessFailure | undefined;
    try {
      await save(bodyOf(sent));
    } catch (error) {
      failure = failureOf(error);
    } finally {
      inFlight.current = false;
      setSubmitting(false);
    }
    if (failure === undefined) return;
    if (failure.kind === "fields") {
      const mapped: Record<string, Partial<Record<LinkField, string>>> = {};
      let unmapped = false;
      for (const { field, reason } of failure.errors) {
        const match = SERVER_FIELD.exec(field);
        const row = match?.[1] === undefined ? undefined : sent[Number(match[1])];
        if (match === null || row === undefined) {
          unmapped = true;
          continue;
        }
        const name: LinkField = match[2] === "relationship" ? "relationship" : "percent";
        mapped[row.key] = { ...mapped[row.key], [name]: t(fieldErrorKey(reason)) };
      }
      if (Object.keys(mapped).length > 0) {
        fail(mapped);
        return;
      }
      if (unmapped) {
        setAlert({ message: t("problem.notProcessed") });
        askFocus("alert");
      }
      return;
    }
    if (failure.kind === "rate-limited") countdown.start(failure.retryAfterSeconds);
    const traceId = "traceId" in failure ? failure.traceId : undefined;
    // The student is the one of this screen: the shared text for an inactive student speaks of enrolling.
    const key: AdminKey =
      failure.kind === "message"
        ? failure.key === "problem.studentNotActive"
          ? "problem.studentNotActiveForGuardians"
          : failure.key
        : failure.kind === "rate-limited"
          ? "problem.rateLimited"
          : "problem.unexpected";
    setAlert({ message: t(key), ...(traceId === undefined ? {} : { traceId }) });
    askFocus("alert");
  }

  function onSubmit(event: SyntheticEvent) {
    event.preventDefault();
    if (inFlight.current) return;
    if (countdown.isActive()) {
      alertElement?.focus();
      return;
    }
    const found = issuesOf(validateLinks(rows));
    if (Object.keys(found).length > 0) {
      fail(found);
      return;
    }
    void send(rows);
  }

  const summary = summaryShown
    ? rows.flatMap((row) =>
        (["relationship", "percent"] as const).flatMap((field) => {
          const message = issues[row.key]?.[field];
          return message === undefined ? [] : [{ controlId: linkControlId(row.key, field), message }];
        }),
      )
    : [];

  return {
    rows,
    rowErrors: issues,
    summary,
    ...(alert === undefined ? {} : { alert }),
    ...(countdown.remainingSeconds === undefined ? {} : { countdown: { remaining: t("countdown.remaining", { seconds: countdown.remainingSeconds }) } }),
    liveMessage: submitting ? t("guardians.links.saving") : countdown.finished ? t("countdown.done") : announcement,
    submitting,
    full: rows.length >= MAX_LINKS,
    attachSummary: setSummaryElement,
    attachAlert: setAlertElement,
    add(guardian) {
      if (rows.length >= MAX_LINKS || rows.some((row) => row.guardian.id === guardian.id)) return;
      const row = draftOf(guardian);
      setRows([...rows, row]);
      setAnnouncement(t("guardians.links.added", { name: choiceNameOf(guardian) }));
      askFocus({ id: linkControlId(row.key, "relationship") });
    },
    remove(key) {
      const row = rows.find((candidate) => candidate.key === key);
      setRows(rows.filter((candidate) => candidate.key !== key));
      setIssues(Object.fromEntries(Object.entries(issues).filter(([name]) => name !== key)));
      if (row !== undefined) setAnnouncement(t("guardians.links.removed", { name: choiceNameOf(row.guardian) }));
      askFocus({ id: ADD_GUARDIAN_CONTROL_ID });
    },
    change(key, patch) {
      const next = rows.map((row) => (row.key === key ? { ...row, ...patch } : row));
      setRows(next);
      if (summaryShown || issues[key] !== undefined) {
        // A row that was refused shows its own reason again as it is edited, and the others keep theirs.
        const again = issuesOf(validateLinks(next));
        setIssues({ ...Object.fromEntries(Object.entries(issues).filter(([name]) => name !== key)), ...(again[key] === undefined ? {} : { [key]: again[key] }) });
      }
    },
    onSubmit,
  };
}

/** Kept next to the hook so the presentation and the tests name the same ids. */
export { linkControlId, linkRemoveId };
