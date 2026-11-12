import { SECTION_IDS } from "./organization-seed.ts";
import { studentId } from "./students-seed.ts";

/**
 * The fictitious enrollments of the simulated API (enrollments cut), over the seeded students and the seeded academic
 * structure. Plain data with fixed identifiers, so a journey can name a row. Nothing here is imported by the app or by
 * the build. The year in progress holds thirty seven enrollments (two pages of 25): 25 active students fill "Segundo A"
 * (capacity 25, so it is full), and twelve more are in the two first-grade sections, with six withdrawals, one graduation and
 * one section transfer in the history. The other active students (36, 43 to 49, 51 to 59) have no enrollment: they are the ones to enroll.
 */
export type EnrollmentStatus = "ACTIVE" | "WITHDRAWN" | "TRANSFERRED" | "GRADUATED";

export interface EnrollmentTransferRow {
  id: string;
  fromSectionId: string;
  toSectionId: string;
  transferredOn: string;
  reason: string | null;
  transferredBy: string;
  createdAt: string;
}

export interface EnrollmentRow {
  id: string;
  studentId: string;
  sectionId: string;
  enrolledOn: string;
  status: EnrollmentStatus;
  withdrawnOn: string | null;
  withdrawalReason: string | null;
  createdAt: string;
  updatedAt: string;
  transfers: EnrollmentTransferRow[];
}

const STAMP = "2026-11-01T12:00:00Z";
export const enrollmentId = (n: number): string => `00000000-0000-4000-8000-${String(7000 + n).padStart(12, "0")}`;
const STAFF = "00000000-0000-4000-8000-000000009001";

/** The students that are enrolled (by number), in the order of the sections they fill. */
const SECOND_A_STUDENTS = [1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 12, 13, 14, 15, 16, 17, 18, 19, 21, 22, 23, 24, 25, 26, 27] as const;

export const emptyEnrollments = (): EnrollmentRow[] => [];

export function seededEnrollments(): EnrollmentRow[] {
  const rows: EnrollmentRow[] = [];
  const add = (studentNumber: number, sectionId: string, override: Partial<EnrollmentRow> = {}): void => {
    rows.push({
      id: enrollmentId(studentNumber),
      studentId: studentId(studentNumber),
      sectionId,
      enrolledOn: "2026-02-02",
      status: "ACTIVE",
      withdrawnOn: null,
      withdrawalReason: null,
      createdAt: STAMP,
      updatedAt: STAMP,
      transfers: [],
      ...override,
    });
  };
  for (const n of SECOND_A_STUDENTS) add(n, SECTION_IDS.secondA);
  // First A: one with a history of one transfer, one active, one withdrawn (who may enroll again), one active.
  add(28, SECTION_IDS.firstA, {
    transfers: [
      {
        id: `00000000-0000-4000-8000-${String(8000 + 28).padStart(12, "0")}`,
        fromSectionId: SECTION_IDS.firstB,
        toSectionId: SECTION_IDS.firstA,
        transferredOn: "2026-03-02",
        reason: "Cambio de turno",
        transferredBy: STAFF,
        createdAt: STAMP,
      },
    ],
  });
  add(29, SECTION_IDS.firstA);
  add(31, SECTION_IDS.firstA, { status: "WITHDRAWN", withdrawnOn: "2026-05-04", withdrawalReason: "Cambio de ciudad" });
  add(32, SECTION_IDS.firstA);
  // First B: one active, one graduated, one active.
  add(33, SECTION_IDS.firstB);
  add(34, SECTION_IDS.firstB, { status: "GRADUATED", withdrawnOn: "2026-11-01" });
  add(35, SECTION_IDS.firstB);
  // Five more students who left the year earlier.
  for (const n of [37, 38, 39, 41, 42] as const) {
    add(n, SECTION_IDS.firstB, { status: "WITHDRAWN", withdrawnOn: "2026-06-01", withdrawalReason: "Otra escuela" });
  }
  return rows;
}
