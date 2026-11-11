/**
 * The fictitious rows of the simulated academic structure (organization-academic-structure cut). Plain data with
 * fixed identifiers, so a journey can name a row in a URL and a test can assert on it. Nothing here is imported by the
 * app or by the build; the simulator and the journeys read it. The rows are the ones a person could meet in a small
 * school: thirty years back to back (enough for a second page), three modalities, five grades and four sections.
 */
export type YearStatus = "PLANNED" | "ACTIVE" | "CLOSED";

export interface YearRow {
  id: string;
  name: string;
  startDate: string;
  endDate: string;
  status: YearStatus;
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface ModalityRow {
  id: string;
  code: string;
  name: string;
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface GradeRow {
  id: string;
  modalityId: string;
  name: string;
  ordinal: number;
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface SectionRow {
  id: string;
  academicYearId: string;
  gradeId: string;
  name: string;
  capacity: number | null;
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface OrganizationRows {
  years: YearRow[];
  modalities: ModalityRow[];
  grades: GradeRow[];
  sections: SectionRow[];
}

const STAMP = "2026-11-01T12:00:00Z";
/** A valid UUID (version 4, RFC variant) that ends in the number given, so the seeded ids read at a glance. */
const uuid = (kind: number, n: number): string => `00000000-0000-4000-8000-${String(kind * 1000 + n).padStart(12, "0")}`;

export const FIRST_YEAR = 1997;
export const LAST_YEAR = 2026;
/** The year name of a calendar year of the seed. */
export const yearName = (calendarYear: number): string => `Año lectivo ${String(calendarYear)}`;
export const yearId = (calendarYear: number): string => uuid(1, calendarYear - FIRST_YEAR + 1);

export const MODALITY_IDS = { primary: uuid(2, 1), secondary: uuid(2, 2), preschool: uuid(2, 3) } as const;
export const GRADE_IDS = {
  first: uuid(3, 1),
  second: uuid(3, 2),
  third: uuid(3, 3),
  seventh: uuid(3, 4),
  eighth: uuid(3, 5),
} as const;
export const SECTION_IDS = { firstA: uuid(4, 1), firstB: uuid(4, 2), secondA: uuid(4, 3), firstAPast: uuid(4, 4) } as const;

export const emptyRows = (): OrganizationRows => ({ years: [], modalities: [], grades: [], sections: [] });

/** Thirty years from 1997 to 2026 (15 January to 30 November each); only the last is in progress, the rest are closed. */
export function seededRows(): OrganizationRows {
  const years: YearRow[] = [];
  for (let calendarYear = FIRST_YEAR; calendarYear <= LAST_YEAR; calendarYear += 1) {
    years.push({
      id: yearId(calendarYear),
      name: yearName(calendarYear),
      startDate: `${String(calendarYear)}-01-15`,
      endDate: `${String(calendarYear)}-11-30`,
      status: calendarYear === LAST_YEAR ? "ACTIVE" : "CLOSED",
      isActive: true,
      createdAt: STAMP,
      updatedAt: STAMP,
    });
  }
  const stamped = { isActive: true, createdAt: STAMP, updatedAt: STAMP };
  return {
    years,
    modalities: [
      { id: MODALITY_IDS.primary, code: "PRI", name: "Primaria", ...stamped },
      { id: MODALITY_IDS.secondary, code: "SEC", name: "Secundaria", ...stamped },
      { id: MODALITY_IDS.preschool, code: "PRE", name: "Prebásica", ...stamped },
    ],
    grades: [
      { id: GRADE_IDS.first, modalityId: MODALITY_IDS.primary, name: "Primero", ordinal: 1, ...stamped },
      { id: GRADE_IDS.second, modalityId: MODALITY_IDS.primary, name: "Segundo", ordinal: 2, ...stamped },
      { id: GRADE_IDS.third, modalityId: MODALITY_IDS.primary, name: "Tercero", ordinal: 3, ...stamped },
      { id: GRADE_IDS.seventh, modalityId: MODALITY_IDS.secondary, name: "Séptimo", ordinal: 1, ...stamped },
      { id: GRADE_IDS.eighth, modalityId: MODALITY_IDS.secondary, name: "Octavo", ordinal: 2, ...stamped },
    ],
    sections: [
      { id: SECTION_IDS.firstA, academicYearId: yearId(LAST_YEAR), gradeId: GRADE_IDS.first, name: "A", capacity: 30, ...stamped },
      { id: SECTION_IDS.firstB, academicYearId: yearId(LAST_YEAR), gradeId: GRADE_IDS.first, name: "B", capacity: null, ...stamped },
      { id: SECTION_IDS.secondA, academicYearId: yearId(LAST_YEAR), gradeId: GRADE_IDS.second, name: "A", capacity: 25, ...stamped },
      { id: SECTION_IDS.firstAPast, academicYearId: yearId(LAST_YEAR - 1), gradeId: GRADE_IDS.first, name: "A", capacity: 28, ...stamped },
    ],
  };
}
