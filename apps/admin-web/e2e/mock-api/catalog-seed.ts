/**
 * The fictitious payment concepts of the simulated catalog (catalog-payment-concepts cut). Plain data with fixed
 * identifiers, so a journey can name a row and a test can assert on it. Nothing here is imported by the app or by the
 * build. Six concepts a person would meet in a small school, one for each category and one of them inactive, and
 * twenty four generic ones after them, so the list needs a second page.
 */
export type PaymentCategory = "TUITION" | "ENROLLMENT" | "TRANSPORT" | "MATERIAL" | "FINE" | "OTHER";

export interface PaymentConceptRow {
  id: string;
  code: string;
  name: string;
  category: PaymentCategory;
  isTaxable: boolean;
  isRecurring: boolean;
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
}

const STAMP = "2026-11-01T12:00:00Z";
/** A valid UUID (version 4, RFC variant) that ends in the number given, so the seeded ids read at a glance. */
export const conceptId = (n: number): string => `00000000-0000-4000-8000-${String(7000 + n).padStart(12, "0")}`;

/** How many concepts the seed holds: the six named ones and the generic ones. */
export const GENERIC_COUNT = 24;
export const TOTAL = 6 + GENERIC_COUNT;

const named: readonly Omit<PaymentConceptRow, "id" | "createdAt" | "updatedAt">[] = [
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
  return [...named, ...generic].map((row, index) => ({ id: conceptId(index + 1), ...row, createdAt: STAMP, updatedAt: STAMP }));
}

export const emptyRows = (): PaymentConceptRow[] => [];
