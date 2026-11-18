/**
 * The fictitious chart of accounts and map of posting accounts of the simulated ledger area (ledger-chart-of-accounts cut).
 * Plain data with fixed identifiers, so a journey can name a row and a test can assert on it. Nothing here is imported by the
 * app or by the build. The codes and names are made up for the simulator only (the real chart is data that the Super
 * Administrator loads and the accountant validates): the six accounts of the suggested chart, an inactive income account and
 * an equity account, and twenty generic expense accounts after them, so the list needs a second page.
 */
export type AccountType = "ASSET" | "LIABILITY" | "EQUITY" | "INCOME" | "EXPENSE";
export type PostingPurpose = "RECEIVABLE" | "REVENUE" | "TAX_PAYABLE";
export type RevenueCategory = "TUITION" | "ENROLLMENT" | "TRANSPORT" | "MATERIAL" | "FINE" | "OTHER";

export interface LedgerAccountRow {
  id: string;
  code: string;
  name: string;
  type: AccountType;
  holdsStudentBalances: boolean;
  active: boolean;
  /** Bumped by every edit; an edit that sends another one is refused with 409 `stale-version`. */
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface PostingRow {
  id: string;
  purpose: PostingPurpose;
  /** Only the revenue has a category; null is the default revenue (and the receivable and the tax payable). */
  conceptCategory: RevenueCategory | null;
  ledgerAccountId: string;
  /** Bumped by every change of the account of the row. */
  version: number;
  updatedAt: string;
}

const STAMP = "2026-11-01T12:00:00Z";

/** A valid UUID (version 4, RFC variant) that ends in the number given, so the seeded ids read at a glance. */
export const accountId = (n: number): string => `00000000-0000-4000-8000-${String(8000 + n).padStart(12, "0")}`;
export const postingId = (n: number): string => `00000000-0000-4000-8000-${String(8500 + n).padStart(12, "0")}`;

/** The accounts of the seed, in the order a journey names them. */
export const ACCOUNT_IDS = {
  receivable: accountId(1),
  cash: accountId(2),
  taxPayable: accountId(3),
  tuition: accountId(4),
  enrollment: accountId(5),
  otherIncome: accountId(6),
  retiredIncome: accountId(7),
  equity: accountId(8),
} as const;

/** How many generic expense accounts the seed holds after the eight named ones. */
export const GENERIC_COUNT = 20;
export const TOTAL = 8 + GENERIC_COUNT;

const named: readonly Omit<LedgerAccountRow, "version" | "createdAt" | "updatedAt">[] = [
  { id: ACCOUNT_IDS.receivable, code: "ACT-001", name: "Cuentas por cobrar a estudiantes (ejemplo)", type: "ASSET", holdsStudentBalances: true, active: true },
  { id: ACCOUNT_IDS.cash, code: "ACT-002", name: "Caja y bancos (ejemplo)", type: "ASSET", holdsStudentBalances: false, active: true },
  { id: ACCOUNT_IDS.taxPayable, code: "PAS-001", name: "Impuesto por pagar (ejemplo)", type: "LIABILITY", holdsStudentBalances: false, active: true },
  { id: ACCOUNT_IDS.tuition, code: "ING-001", name: "Ingresos por colegiatura (ejemplo)", type: "INCOME", holdsStudentBalances: false, active: true },
  { id: ACCOUNT_IDS.enrollment, code: "ING-002", name: "Ingresos por matrícula (ejemplo)", type: "INCOME", holdsStudentBalances: false, active: true },
  { id: ACCOUNT_IDS.otherIncome, code: "ING-003", name: "Otros ingresos (ejemplo)", type: "INCOME", holdsStudentBalances: false, active: true },
  { id: ACCOUNT_IDS.retiredIncome, code: "ING-099", name: "Ingreso retirado (ejemplo)", type: "INCOME", holdsStudentBalances: false, active: false },
  { id: ACCOUNT_IDS.equity, code: "PAT-001", name: "Patrimonio (ejemplo)", type: "EQUITY", holdsStudentBalances: false, active: true },
];

/** The name of a generic account, which is also its text in the list. */
export const genericAccountName = (n: number): string => `Gasto genérico ${String(n).padStart(2, "0")} (ejemplo)`;

export const emptyAccounts = (): LedgerAccountRow[] => [];

export function seededAccounts(): LedgerAccountRow[] {
  const generic = Array.from({ length: GENERIC_COUNT }, (_, index): Omit<LedgerAccountRow, "version" | "createdAt" | "updatedAt"> => ({
    id: accountId(9 + index),
    code: `GAS-${String(index + 1).padStart(3, "0")}`,
    name: genericAccountName(index + 1),
    type: "EXPENSE",
    holdsStudentBalances: false,
    active: true,
  }));
  return [...named, ...generic].map((row) => ({ ...row, version: 0, createdAt: STAMP, updatedAt: STAMP }));
}

export type MapSeed = "empty" | "partial" | "complete";

/** The map of the seed: nothing set, the two main accounts only, or everything the accrual of the seeded concepts needs. */
export function seededMap(seed: MapSeed): PostingRow[] {
  const row = (n: number, purpose: PostingPurpose, conceptCategory: RevenueCategory | null, ledgerAccountId: string): PostingRow => ({
    id: postingId(n),
    purpose,
    conceptCategory,
    ledgerAccountId,
    version: 0,
    updatedAt: STAMP,
  });
  if (seed === "empty") return [];
  const main = [row(1, "RECEIVABLE", null, ACCOUNT_IDS.receivable), row(2, "TAX_PAYABLE", null, ACCOUNT_IDS.taxPayable)];
  if (seed === "partial") return main;
  return [
    ...main,
    row(3, "REVENUE", null, ACCOUNT_IDS.otherIncome),
    row(4, "REVENUE", "TUITION", ACCOUNT_IDS.tuition),
    row(5, "REVENUE", "ENROLLMENT", ACCOUNT_IDS.enrollment),
  ];
}
