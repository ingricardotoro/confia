/**
 * The fictitious payment concepts of the simulated catalog (catalog-payment-concepts cut). Plain data with fixed
 * identifiers, so a journey can name a row and a test can assert on it. Nothing here is imported by the app or by the
 * build. Six concepts a person would meet in a small school, one for each category and one of them inactive, and
 * twenty four generic ones after them, so the list needs a second page.
 */
import { GRADE_IDS, yearId } from "./organization-seed.ts";

export type PaymentCategory = "TUITION" | "ENROLLMENT" | "TRANSPORT" | "MATERIAL" | "FINE" | "OTHER";

export interface PaymentConceptRow {
  id: string;
  code: string;
  name: string;
  category: PaymentCategory;
  isTaxable: boolean;
  isRecurring: boolean;
  isActive: boolean;
  /** Bumped by every edit; an edit that sends another one is refused with 409 `stale-version`. */
  version: number;
  createdAt: string;
  updatedAt: string;
}

const STAMP = "2026-11-01T12:00:00Z";
/** A valid UUID (version 4, RFC variant) that ends in the number given, so the seeded ids read at a glance. */
export const conceptId = (n: number): string => `00000000-0000-4000-8000-${String(7000 + n).padStart(12, "0")}`;

/** How many concepts the seed holds: the six named ones and the generic ones. */
export const GENERIC_COUNT = 24;
export const TOTAL = 6 + GENERIC_COUNT;

const named: readonly Omit<PaymentConceptRow, "id" | "version" | "createdAt" | "updatedAt">[] = [
  { code: "COL", name: "Colegiatura", category: "TUITION", isTaxable: true, isRecurring: true, isActive: true },
  { code: "MAT", name: "Matrícula anual", category: "ENROLLMENT", isTaxable: false, isRecurring: false, isActive: true },
  { code: "TRA", name: "Transporte escolar", category: "TRANSPORT", isTaxable: true, isRecurring: true, isActive: true },
  { code: "LIB", name: "Libros y útiles", category: "MATERIAL", isTaxable: true, isRecurring: false, isActive: true },
  { code: "MUL", name: "Multa por retraso", category: "FINE", isTaxable: false, isRecurring: false, isActive: true },
  { code: "OTR", name: "Otros ingresos", category: "OTHER", isTaxable: false, isRecurring: false, isActive: false },
];

/** The name of a generic concept, which is also its text in the list. */
export const genericName = (n: number): string => `Concepto genérico ${String(n).padStart(2, "0")}`;
export const genericCode = (n: number): string => `X-${String(n).padStart(2, "0")}`;

export function seededRows(): PaymentConceptRow[] {
  const generic = Array.from({ length: GENERIC_COUNT }, (_, index) => ({
    code: genericCode(index + 1),
    name: genericName(index + 1),
    category: "OTHER" as const,
    isTaxable: false,
    isRecurring: false,
    isActive: true,
  }));
  return [...named, ...generic].map((row, index) => ({ id: conceptId(index + 1), ...row, version: 0, createdAt: STAMP, updatedAt: STAMP }));
}

export const emptyRows = (): PaymentConceptRow[] => [];

// The price lists (catalog-price-lists cut). Amounts are strings, as they travel; the simulator never turns one into a number.
export type PriceListScope = "INSTITUTION" | "MODALITY" | "GRADE";
export type PriceListStatus = "ACTIVE" | "ARCHIVED";

export interface PriceListRow {
  id: string;
  academicYearId: string;
  name: string;
  scope: PriceListScope;
  scopeRefId: string | null;
  status: PriceListStatus;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface PriceItemRow {
  id: string;
  priceListId: string;
  paymentConceptId: string;
  /** Exactly as stored: up to two decimals. Answered as stored. */
  amount: string;
  currency: "HNL" | "USD";
  validFrom: string;
  /** Inclusive; null while the validity is open. */
  validTo: string | null;
  createdAt: string;
}

export interface PriceListRows {
  lists: PriceListRow[];
  items: PriceItemRow[];
}

/** The lists of the seed, in the order a journey names them. */
export const LIST_IDS = { institution2026: listId(1), first2026: listId(2), archived2025: listId(3) } as const;
/** How many generic lists follow the three named ones, so the list needs a second page. */
export const GENERIC_LISTS = 24;
export const TOTAL_LISTS = 3 + GENERIC_LISTS;
export const genericListName = (n: number): string => `Lista genérica ${String(n).padStart(2, "0")}`;

/** A valid UUID (version 4, RFC variant) for a list or a price, ending in the kind and number given. */
function listId(n: number): string {
  return `00000000-0000-4000-8000-${String(9000 + n).padStart(12, "0")}`;
}
export const priceId = (n: number): string => `00000000-0000-4000-8000-${String(8000 + n).padStart(12, "0")}`;

const YEAR_2026 = yearId(2026);
const YEAR_2025 = yearId(2025);
const YEAR_2024 = yearId(2024);
const GRADE_FIRST = GRADE_IDS.first;

export function seededPriceLists(): PriceListRows {
  const stamped = { version: 0, createdAt: STAMP, updatedAt: STAMP };
  const named: PriceListRow[] = [
    { id: LIST_IDS.institution2026, academicYearId: YEAR_2026, name: "2026 — Institución", scope: "INSTITUTION", scopeRefId: null, status: "ACTIVE", ...stamped },
    { id: LIST_IDS.first2026, academicYearId: YEAR_2026, name: "2026 — Primero", scope: "GRADE", scopeRefId: GRADE_FIRST, status: "ACTIVE", ...stamped },
    { id: LIST_IDS.archived2025, academicYearId: YEAR_2025, name: "2025 — Institución", scope: "INSTITUTION", scopeRefId: null, status: "ARCHIVED", ...stamped },
  ];
  const generic = Array.from({ length: GENERIC_LISTS }, (_, index): PriceListRow => ({
    id: listId(4 + index),
    academicYearId: YEAR_2024,
    name: genericListName(index + 1),
    scope: "INSTITUTION",
    scopeRefId: null,
    status: "ACTIVE",
    ...stamped,
  }));
  const item = (n: number, priceListId: string, concept: number, amount: string, currency: "HNL" | "USD", validFrom: string, validTo: string | null): PriceItemRow => ({
    id: priceId(n),
    priceListId,
    paymentConceptId: conceptId(concept),
    amount,
    currency,
    validFrom,
    validTo,
    createdAt: STAMP,
  });
  return {
    lists: [...named, ...generic],
    // Colegiatura (1) has a closed validity and the open one that replaced it; Matrícula (2) and Transporte (3) one each.
    items: [
      item(1, LIST_IDS.institution2026, 1, "1500.00", "HNL", "2026-01-01", "2026-06-30"),
      item(2, LIST_IDS.institution2026, 1, "1650.00", "HNL", "2026-07-01", null),
      item(3, LIST_IDS.institution2026, 2, "800.00", "HNL", "2026-01-01", null),
      item(4, LIST_IDS.institution2026, 3, "35.50", "USD", "2026-01-01", null),
      item(5, LIST_IDS.archived2025, 1, "1400.00", "HNL", "2025-01-01", null),
    ],
  };
}

export const emptyPriceLists = (): PriceListRows => ({ lists: [], items: [] });
