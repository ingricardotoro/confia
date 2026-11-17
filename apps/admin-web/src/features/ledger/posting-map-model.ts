import type { PostingAccountEntryRequest, PostingAccountResponseOutput } from "@confia/contracts/admin";
import type { LedgerAccountType, PostingPurpose } from "../../shared/api/ledger-operations.ts";

/** The categories of a payment concept, in the order a person reads them. The revenue account of each is one row of the map. */
export const REVENUE_CATEGORIES = ["TUITION", "ENROLLMENT", "TRANSPORT", "MATERIAL", "FINE", "OTHER"] as const;
export type RevenueCategory = (typeof REVENUE_CATEGORIES)[number];

/** The name of one row of the map: the purpose, and for the revenue the category (or `DEFAULT`). It is also the end of its catalog keys. */
export type RoleId = "RECEIVABLE" | "TAX_PAYABLE" | "REVENUE_DEFAULT" | `REVENUE_${RevenueCategory}`;

/**
 * One row of the map and the kind of account it needs. The type rule is the one the server applies (docs/cortes/ledger-chart-of-accounts.md):
 * the receivable is an asset that holds student balances, the revenue an income account and the tax payable a liability, the last two
 * without student balances. The screen filters its options by this rule only to spare the person a refusal; the server decides.
 */
export interface PostingRole {
  readonly id: RoleId;
  readonly purpose: PostingPurpose;
  /** Only the revenue has a category; `null` is the receivable, the tax payable and the default revenue. */
  readonly category: RevenueCategory | null;
  readonly accountType: LedgerAccountType;
  readonly holdsStudentBalances: boolean;
  readonly group: "core" | "revenue";
}

export const POSTING_ROLES: readonly PostingRole[] = [
  { id: "RECEIVABLE", purpose: "RECEIVABLE", category: null, accountType: "ASSET", holdsStudentBalances: true, group: "core" },
  { id: "TAX_PAYABLE", purpose: "TAX_PAYABLE", category: null, accountType: "LIABILITY", holdsStudentBalances: false, group: "core" },
  { id: "REVENUE_DEFAULT", purpose: "REVENUE", category: null, accountType: "INCOME", holdsStudentBalances: false, group: "revenue" },
  ...REVENUE_CATEGORIES.map(
    (category): PostingRole => ({
      id: `REVENUE_${category}`,
      purpose: "REVENUE",
      category,
      accountType: "INCOME",
      holdsStudentBalances: false,
      group: "revenue",
    }),
  ),
];

/** What the server holds for one row of the map: the row, its version and the account it points at. */
export interface SavedEntry {
  readonly id: string;
  readonly ledgerAccountId: string;
  readonly version: number;
}

/** The saved rows by role. A row the server returns that this screen does not know (a category added later) is left out. */
export function savedByRole(items: readonly PostingAccountResponseOutput[]): ReadonlyMap<RoleId, SavedEntry> {
  const byRole = new Map<RoleId, SavedEntry>();
  for (const item of items) {
    const role = POSTING_ROLES.find((candidate) => candidate.purpose === item.purpose && candidate.category === (item.conceptCategory ?? null));
    if (role !== undefined) byRole.set(role.id, { id: item.id, ledgerAccountId: item.ledgerAccountId, version: item.version });
  }
  return byRole;
}

/** Whether an account meets the type rule of a row. */
export const accountFits = (role: PostingRole, account: { readonly type: string; readonly holdsStudentBalances: boolean }): boolean =>
  account.type === role.accountType && account.holdsStudentBalances === role.holdsStudentBalances;

/**
 * The rows that are not set and keep the accrual from running: the receivable, the tax payable, and the default revenue
 * while a category has none of its own. (The server refuses a run only for the categories and the tax the plan really uses,
 * so this is a warning about the map, not a verdict on a run: the preview of the accrual has the verdict.)
 */
export function missingRoles(saved: ReadonlyMap<RoleId, SavedEntry>): readonly RoleId[] {
  const missing: RoleId[] = [];
  if (!saved.has("RECEIVABLE")) missing.push("RECEIVABLE");
  if (!saved.has("TAX_PAYABLE")) missing.push("TAX_PAYABLE");
  const categoryWithoutOwn = REVENUE_CATEGORIES.some((category) => !saved.has(`REVENUE_${category}`));
  if (categoryWithoutOwn && !saved.has("REVENUE_DEFAULT")) missing.push("REVENUE_DEFAULT");
  return missing;
}

/**
 * The entries to send: only the rows whose chosen account differs from the saved one, each with the version of its
 * row (none for a row never set). A row left as it was is not sent, so a save never touches what the person did not change.
 */
export function changedEntries(saved: ReadonlyMap<RoleId, SavedEntry>, draft: Readonly<Partial<Record<RoleId, string>>>): PostingAccountEntryRequest[] {
  const entries: PostingAccountEntryRequest[] = [];
  for (const role of POSTING_ROLES) {
    const chosen = draft[role.id];
    const current = saved.get(role.id);
    if (chosen === undefined || chosen === "" || chosen === current?.ledgerAccountId) continue;
    entries.push({
      purpose: role.purpose,
      ...(role.category === null ? {} : { conceptCategory: role.category }),
      ledgerAccountId: chosen,
      ...(current === undefined ? {} : { version: current.version }),
    });
  }
  return entries;
}
