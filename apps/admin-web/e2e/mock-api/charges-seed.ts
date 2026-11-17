/**
 * The fictitious charge plans of the simulated charges area (charges-plans cut). Plain data with fixed identifiers, so a
 * journey can name a row in a URL and a test can assert on it. Nothing here is imported by the app or by the build. A plan
 * points at rows of the simulated academic structure and its items at concepts of the simulated catalog, by the ids those
 * seeds fix. Four named plans, one in each state a person meets, and twenty four generic drafts after them, so the list
 * needs a second page.
 */
import { conceptId } from "./catalog-seed.ts";
import { GRADE_IDS, LAST_YEAR, MODALITY_IDS, SECTION_IDS, yearId } from "./organization-seed.ts";

export type PlanScope = "MODALITY" | "GRADE" | "SECTION";
export type PlanFrequency = "MONTHLY" | "BIMONTHLY" | "ANNUAL" | "ONE_TIME";
export type PlanStatus = "DRAFT" | "ACTIVE" | "ARCHIVED";

export interface ChargePlanRow {
  id: string;
  academicYearId: string;
  name: string;
  targetScope: PlanScope;
  targetRefId: string;
  frequency: PlanFrequency;
  dueDayOfMonth: number;
  status: PlanStatus;
  /** Bumped by every edit; an edit that sends another one is refused with 409 `stale-version`. */
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface ChargePlanItemRow {
  id: string;
  planId: string;
  paymentConceptId: string;
  periodsCount: number;
  firstPeriodOn: string;
  createdAt: string;
}

export interface ChargePlans {
  plans: ChargePlanRow[];
  items: ChargePlanItemRow[];
}

/** A valid UUID (version 4, RFC variant) that ends in the number given, so the seeded ids read at a glance. */
export const planId = (n: number): string => `00000000-0000-4000-8000-${String(9000 + n).padStart(12, "0")}`;
export const planItemId = (n: number): string => `00000000-0000-4000-8000-${String(9500 + n).padStart(12, "0")}`;

/** The plans of the seed, in the order a journey names them. */
export const PLAN_IDS = { active: planId(1), draftEmpty: planId(2), archived: planId(3), draftWithItem: planId(4) } as const;

/** How many generic drafts the seed holds after the four named plans. */
export const GENERIC_COUNT = 24;
export const TOTAL = 4 + GENERIC_COUNT;

/** The name of a generic plan, which is also its text in the list. */
export const genericPlanName = (n: number): string => `Plan genérico ${String(n).padStart(2, "0")}`;

const STAMP = "2026-11-01T12:00:00Z";

export const emptyPlans = (): ChargePlans => ({ plans: [], items: [] });

export function seededPlans(): ChargePlans {
  const year = yearId(LAST_YEAR);
  const named: Omit<ChargePlanRow, "createdAt" | "updatedAt">[] = [
    {
      id: PLAN_IDS.active,
      academicYearId: year,
      name: "Primaria 2026",
      targetScope: "GRADE",
      targetRefId: GRADE_IDS.first,
      frequency: "MONTHLY",
      dueDayOfMonth: 5,
      status: "ACTIVE",
      version: 2,
    },
    {
      id: PLAN_IDS.draftEmpty,
      academicYearId: year,
      name: "Secundaria 2026",
      targetScope: "MODALITY",
      targetRefId: MODALITY_IDS.secondary,
      frequency: "BIMONTHLY",
      dueDayOfMonth: 10,
      status: "DRAFT",
      version: 0,
    },
    {
      id: PLAN_IDS.archived,
      academicYearId: year,
      name: "Sección A de primero 2026",
      targetScope: "SECTION",
      targetRefId: SECTION_IDS.firstA,
      frequency: "ONE_TIME",
      dueDayOfMonth: 15,
      status: "ARCHIVED",
      version: 3,
    },
    {
      id: PLAN_IDS.draftWithItem,
      academicYearId: year,
      name: "Segundo grado 2026",
      targetScope: "GRADE",
      targetRefId: GRADE_IDS.second,
      frequency: "MONTHLY",
      dueDayOfMonth: 5,
      status: "DRAFT",
      version: 1,
    },
  ];
  const generic: Omit<ChargePlanRow, "createdAt" | "updatedAt">[] = Array.from({ length: GENERIC_COUNT }, (_, index) => ({
    id: planId(5 + index),
    academicYearId: year,
    name: genericPlanName(index + 1),
    targetScope: "MODALITY" as const,
    targetRefId: MODALITY_IDS.primary,
    frequency: "MONTHLY" as const,
    dueDayOfMonth: 5,
    status: "DRAFT" as const,
    version: 0,
  }));
  // A later stamp for each plan, so the order by creation is not a tie.
  const plans = [...named, ...generic].map((plan, index) => {
    const stamp = new Date(Date.parse(STAMP) + index * 60_000).toISOString();
    return { ...plan, createdAt: stamp, updatedAt: stamp };
  });
  const item = (n: number, plan: string, concept: number, periodsCount: number, firstPeriodOn: string): ChargePlanItemRow => ({
    id: planItemId(n),
    planId: plan,
    paymentConceptId: conceptId(concept),
    periodsCount,
    firstPeriodOn,
    createdAt: STAMP,
  });
  return {
    plans,
    // Concept 1 is "Colegiatura" and concept 2 "Matrícula anual" in the catalog seed.
    items: [
      item(1, PLAN_IDS.active, 1, 10, `${String(LAST_YEAR)}-02-01`),
      item(2, PLAN_IDS.active, 2, 1, `${String(LAST_YEAR)}-01-01`),
      item(3, PLAN_IDS.archived, 2, 1, `${String(LAST_YEAR)}-01-01`),
      item(4, PLAN_IDS.draftWithItem, 1, 10, `${String(LAST_YEAR)}-02-01`),
    ],
  };
}
