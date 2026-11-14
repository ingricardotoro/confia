import type { StudentEnrollmentResponseOutput } from "@confia/contracts/admin";

export type Enrollment = StudentEnrollmentResponseOutput;
export type EnrollmentTransfer = NonNullable<Enrollment["transfers"]>[number];
export type EnrollmentStatus = Enrollment["status"];
export type ExitKind = Exclude<EnrollmentStatus, "ACTIVE">;

/** The name a notice and a button use for the student of an enrollment. Names live in the DOM only. */
export const studentNameOf = (row: Pick<Enrollment, "studentFirstName" | "studentLastName">): string =>
  `${row.studentFirstName} ${row.studentLastName}`;

/** The student a search found: what the list needs to name and choose one. */
export interface StudentChoice {
  readonly id: string;
  readonly studentCode: string;
  readonly firstName: string;
  readonly lastName: string;
}

export const choiceNameOf = (student: Pick<StudentChoice, "firstName" | "lastName">): string => `${student.firstName} ${student.lastName}`;

/** The id of the "Ver detalle" button of a row: where the focus goes when the row loses its other actions. */
export const detailButtonId = (enrollmentId: string): string => `enrollment-detail-${enrollmentId}`;

/** Today in Honduras (`YYYY-MM-DD`), the day the rules of the server count from; the date inputs start on it. */
export function todayIso(now: Date = new Date()): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "America/Tegucigalpa", year: "numeric", month: "2-digit", day: "2-digit" }).format(now);
}

export interface OptionGroup {
  /** Already translated. */
  readonly label: string;
  readonly options: readonly { readonly value: string; readonly label: string }[];
}

/**
 * The active sections of a year as the groups of a select, "Modalidad · Grado" each: the groups in alphabetical order and the
 * sections in the order the server gave. A section whose grade is unknown goes in the last group, named by `unknown`.
 */
export function groupSections(
  sections: readonly { readonly id: string; readonly name: string; readonly gradeId: string; readonly isActive: boolean }[],
  gradeLabels: ReadonlyMap<string, string>,
  unknown: string,
  exceptId?: string,
): OptionGroup[] {
  const groups = new Map<string, { value: string; label: string }[]>();
  for (const section of sections) {
    if (!section.isActive || section.id === exceptId) continue;
    const label = gradeLabels.get(section.gradeId) ?? unknown;
    groups.set(label, [...(groups.get(label) ?? []), { value: section.id, label: section.name }]);
  }
  return [...groups.entries()]
    .sort(([a], [b]) => (a === unknown ? 1 : b === unknown ? -1 : a.localeCompare(b, "es-HN")))
    .map(([label, options]) => ({ label, options }));
}
