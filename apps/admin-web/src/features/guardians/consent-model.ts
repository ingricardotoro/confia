import type { ConsentChangeResponseOutput, GuardianConsentsResponseOutput, RecordConsentRequest } from "@confia/contracts/admin";

export type ConsentChannel = RecordConsentRequest["channel"];
export type ConsentPurpose = RecordConsentRequest["purpose"];
export type ConsentAction = RecordConsentRequest["action"];
export type ConsentEvidence = RecordConsentRequest["evidenceSource"];
export type ConsentStanding = GuardianConsentsResponseOutput["states"][number];
export type ConsentEvent = GuardianConsentsResponseOutput["history"][number];
export type ConsentChange = ConsentChangeResponseOutput;

/** Channel by channel, institutional before marketing: the order the server serves the six combinations in. */
export const CONSENT_CHANNELS: readonly ConsentChannel[] = ["EMAIL", "SMS", "WHATSAPP"];
export const CONSENT_PURPOSES: readonly ConsentPurpose[] = ["INSTITUTIONAL", "MARKETING"];
/** The two sources the administrative API accepts; the portal form belongs to the portal. */
export const CONSENT_EVIDENCES: readonly ConsentEvidence[] = ["SIGNED_PAPER_FORM", "ADMIN_REGISTERED"];

/** The form of a policy version, anchored (the generated pattern is not): 1 to 32 letters, digits, dots, dashes and underscores. */
const POLICY_VERSION = /^[A-Za-z0-9._-]{1,32}$/;

export interface ConsentTarget {
  readonly channel: ConsentChannel;
  readonly purpose: ConsentPurpose;
  readonly action: ConsentAction;
}

export interface ConsentDraft {
  readonly policyVersion: string;
  /** Empty until the person chooses: the form never preselects a source of evidence. */
  readonly evidence: ConsentEvidence | "";
}

export const EMPTY_CONSENT_DRAFT: ConsentDraft = {
  policyVersion: "",
  evidence: "",
};

export type ConsentField = "policyVersion" | "evidenceSource";

/** The id of the button of a cell, so the focus can go back to it. */
export const consentActionId = ({ channel, purpose, action }: ConsentTarget): string => `consent-${channel}-${purpose}-${action}`.toLowerCase();

/** The id of the control of a field of the open form, so the summary can link to it and the server's errors can name it. */
export const CONSENT_CONTROL_IDS: Readonly<Record<ConsentField, string>> = {
  policyVersion: "consent-policy-version",
  evidenceSource: "consent-evidence",
};

/** The reasons (by field) a draft is refused for before anything is sent; the server decides the rest. */
export function validateConsent(target: ConsentTarget, draft: ConsentDraft): Partial<Record<ConsentField, string>> {
  const issues: Partial<Record<ConsentField, string>> = {};
  if (target.action === "GRANT") {
    const version = draft.policyVersion.trim();
    if (version === "") issues.policyVersion = "required";
    else if (!POLICY_VERSION.test(version)) issues.policyVersion = "policy-version-format";
  }
  if (draft.evidence === "") issues.evidenceSource = "required";
  return issues;
}

/** The body of the request: the version travels only on a grant, and a withdrawal omits it. */
export function consentBody(target: ConsentTarget, draft: ConsentDraft): RecordConsentRequest {
  return {
    channel: target.channel,
    purpose: target.purpose,
    action: target.action,
    evidenceSource: draft.evidence === "" ? "ADMIN_REGISTERED" : draft.evidence,
    ...(target.action === "GRANT" ? { policyVersion: draft.policyVersion.trim() } : {}),
  };
}

export const standingOf = (states: readonly ConsentStanding[], channel: ConsentChannel, purpose: ConsentPurpose): ConsentStanding | undefined =>
  states.find((entry) => entry.channel === channel && entry.purpose === purpose);
