import type { ReplaceStudentGuardiansRequest, StudentGuardianResponseOutput } from "@confia/contracts/admin";
import { formatPercent } from "../../shared/format/format-percent.ts";
import type { FieldReason } from "../../shared/forms/use-access-form.ts";

export type Relationship = StudentGuardianResponseOutput["relationship"];

export const RELATIONSHIPS: readonly Relationship[] = ["FATHER", "MOTHER", "LEGAL_GUARDIAN", "SELF", "OTHER"];

/** The most links the API takes for one student. */
export const MAX_LINKS = 20;

/** A guardian the staff can add: what the search found, or what a current link already says. */
export interface GuardianChoice {
  readonly id: string;
  readonly guardianCode: string;
  readonly firstName: string;
  readonly lastName: string;
  readonly status: "ACTIVE" | "INACTIVE";
}

export const choiceNameOf = (guardian: Pick<GuardianChoice, "firstName" | "lastName">): string => `${guardian.firstName} ${guardian.lastName}`;

/**
 * One row of the set being edited. The percentage is the text the person types: the browser never turns it into a number
 * until the body is built, and it never adds, compares to a total or rounds it. `key` is local and names the row's
 * controls.
 */
export interface LinkDraft {
  readonly key: string;
  readonly guardian: GuardianChoice;
  /** A `Relationship`, or empty while the person has not chosen one. */
  readonly relationship: string;
  readonly percent: string;
  readonly primary: boolean;
  readonly billing: boolean;
}

export const newKey = (): string => crypto.randomUUID();

/** The set the server holds, as rows to edit. The percentage keeps the two decimals the server gave. */
export function draftsOf(links: readonly StudentGuardianResponseOutput[]): LinkDraft[] {
  return links.map((link) => ({
    key: newKey(),
    guardian: {
      id: link.guardianId,
      guardianCode: link.guardianCode,
      firstName: link.guardianFirstName,
      lastName: link.guardianLastName,
      status: link.guardianStatus,
    },
    relationship: link.relationship,
    percent: formatPercent(link.financialResponsibilityPercent),
    primary: link.isPrimaryPayer,
    billing: link.isBillingRecipient,
  }));
}

/** A row for a guardian just chosen: nothing but the guardian, so the person must say the parentesco and the percentage. */
export const draftOf = (guardian: GuardianChoice): LinkDraft => ({ key: newKey(), guardian, relationship: "", percent: "", primary: false, billing: false });

const PERCENT_SHAPE = /^\d{1,3}(?:\.(\d{1,2}))?$/;

/**
 * What is wrong with a typed percentage, by looking at the text and never at a sum: it is empty, it is not a number with
 * at most two decimals, or it is outside 0.01 to 100. The range is read from the digits (an integer part over 100, 100
 * with a non-zero decimal, or all zeros). Whether the percentages of the set add up to 100 is the server's to say.
 */
export function percentProblem(text: string): FieldReason | undefined {
  if (text === "") return "required";
  const shape = PERCENT_SHAPE.exec(text);
  if (shape === null) return "percent-format";
  const [whole = "", decimals = ""] = text.split(".");
  if (/^0+$/.test(whole) && !/[1-9]/.test(decimals)) return "percent-range";
  if (Number(whole) > 100 || (Number(whole) === 100 && /[1-9]/.test(decimals))) return "percent-range";
  return undefined;
}

export type LinkField = "relationship" | "percent";

/** The id of a control of a row: the error summary links to it. */
export const linkControlId = (key: string, field: LinkField): string => `link-${field}-${key}`;
export const linkRemoveId = (key: string): string => `link-remove-${key}`;

/** The reasons each row is refused before sending, by row key and field. Empty when the rows are fine to send. */
export function validateLinks(rows: readonly LinkDraft[]): Record<string, Partial<Record<LinkField, FieldReason>>> {
  const found: Record<string, Partial<Record<LinkField, FieldReason>>> = {};
  for (const row of rows) {
    const relationship: FieldReason | undefined = row.relationship === "" ? "required" : undefined;
    const percent = percentProblem(row.percent);
    if (relationship !== undefined || percent !== undefined) {
      found[row.key] = { ...(relationship === undefined ? {} : { relationship }), ...(percent === undefined ? {} : { percent }) };
    }
  }
  return found;
}

/**
 * The body of the replacement. The percentage travels as a JSON number, as the contract says: `Number("33.33")` is a
 * conversion of the typed text, not arithmetic, and the server reads it back as an exact decimal.
 */
export function bodyOf(rows: readonly LinkDraft[]): ReplaceStudentGuardiansRequest {
  return {
    links: rows.map((row) => ({
      guardianId: row.guardian.id,
      relationship: row.relationship as Relationship,
      financialResponsibilityPercent: Number(row.percent),
      isPrimaryPayer: row.primary,
      isBillingRecipient: row.billing,
    })),
  };
}
