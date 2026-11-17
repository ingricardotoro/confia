import type { Money } from "@confia/contracts/admin";
import type { StatementLine, StatementLineKind } from "../../shared/api/charges-operations.ts";
import { DEFAULT_ADMIN_LANGUAGE } from "../../i18n/resources.ts";

/**
 * What the statement needs to decide about the figures the server sends. It reads them; it never adds, subtracts or rounds one.
 * The only thing it asks of an amount is its sign, a look at the text, because the label of a balance depends on it.
 */

/** Who owes whom, which a figure alone does not say: positive is what the student owes, negative is a balance in their favor. */
export type BalanceSide = "owed" | "credit" | "zero";

/** The side of a balance by the text the server sent (`-` before a figure that is not zero). Nothing is parsed into a number. */
export function balanceSideOf(money: Money): BalanceSide {
  const digits = money.amount.replace(/^-/, "");
  if (/^0*(\.0*)?$/.test(digits)) return "zero";
  return money.amount.startsWith("-") ? "credit" : "owed";
}

/** Whether the period the person typed is a period: it ends on or after the day it starts. Dates are `yyyy-MM-dd`, so text order is date order. */
export type PeriodProblem = "from-after-to";

export function periodProblemOf(from: string | undefined, to: string | undefined): PeriodProblem | undefined {
  if (from === undefined || to === undefined || from === "" || to === "") return undefined;
  return from > to ? "from-after-to" : undefined;
}

/** What a line of the statement is about: a charge (concept and period) or another movement (its kind and its reason). */
export type LineDescription =
  | { readonly kind: "charge"; readonly concept: string | undefined; readonly periodStart: string }
  | { readonly kind: "movement"; readonly lineKind: StatementLineKind; readonly reason: string | undefined };

export function describeLine(line: StatementLine): LineDescription {
  if (line.charge !== undefined) {
    return { kind: "charge", concept: line.charge.conceptName ?? line.charge.conceptCode ?? undefined, periodStart: line.charge.periodStart };
  }
  return { kind: "movement", lineKind: line.kind, reason: line.reason ?? undefined };
}

/** The due date of a line, for the lines that come from a charge. */
export const dueOnOf = (line: StatementLine): string | undefined => line.charge?.dueOn;

/** Which column holds the figure of a line, so the badge says «Cargo» or «Abono». Both columns are always present and one is zero. */
export type LineDirection = "debit" | "credit";

export function directionOf(line: StatementLine): LineDirection | undefined {
  if (balanceSideOf(line.debit) !== "zero") return "debit";
  if (balanceSideOf(line.credit) !== "zero") return "credit";
  return undefined;
}

/** The name of a currency as Intl gives it («lempira hondureño»), or its code when Intl does not know it. */
export function currencyNameOf(code: string): string {
  try {
    return new Intl.DisplayNames([DEFAULT_ADMIN_LANGUAGE], { type: "currency" }).of(code) ?? code;
  } catch {
    return code;
  }
}
