import type { AdminKey } from "../../i18n/resources.ts";

/** The kinds of discrepancy the screen has a text for, as the server names them. */
export const KNOWN_FINDING_KINDS = [
  "UNBALANCED_TRANSACTION",
  "CHARGE_POSTING_MISSING",
  "CHARGE_POSTING_DUPLICATE",
  "CHARGE_TOTAL_MISMATCH",
  "VOID_REVERSAL_MISSING",
  "VOID_REVERSAL_DUPLICATE",
  "REVERSAL_WITHOUT_VOID",
  "ORPHAN_POSTING",
  "RECEIVABLE_MISMATCH",
] as const;

export type KnownFindingKind = (typeof KNOWN_FINDING_KINDS)[number];

/** The kind as the screen knows it: a kind the server adds later reads as `unknown` and never as a blank. */
export const findingKindOf = (kind: string): KnownFindingKind | "unknown" => KNOWN_FINDING_KINDS.find((known) => known === kind) ?? "unknown";

export const findingKindKeyOf = (kind: string): AdminKey => `charges.integrity.kinds.${findingKindOf(kind)}`;
