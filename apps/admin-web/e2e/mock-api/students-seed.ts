/**
 * The fictitious students of the simulated registry (students-registry cut). Plain data with fixed identifiers, so a
 * journey can name a row and a test can assert on it. Nothing here is imported by the app or by the build. Sixty
 * students, two for each of thirty surnames (enough for three pages of 25), a third of them with a national id and
 * every tenth inactive. The full national id lives only in the simulator, apart from the rows: no response carries it.
 */
export type Sex = "F" | "M" | "UNSPECIFIED";
export type StudentStatus = "ACTIVE" | "INACTIVE";

export interface StudentRow {
  id: string;
  studentCode: string;
  firstName: string;
  lastName: string;
  birthDate: string;
  sex: Sex;
  status: StudentStatus;
  createdAt: string;
  updatedAt: string;
}

export interface StudentRows {
  students: StudentRow[];
  /** The full national id of a student that has one, as the simulator keeps it: never in a row, never in a response. */
  documents: Map<string, string>;
}

const STAMP = "2026-11-01T12:00:00Z";
/** A valid UUID (version 4, RFC variant) that ends in the number given, so the seeded ids read at a glance. */
export const studentId = (n: number): string => `00000000-0000-4000-8000-${String(5000 + n).padStart(12, "0")}`;
export const studentCodeOf = (n: number): string => `E-${String(n).padStart(6, "0")}`;

export const SURNAMES = [
  "Aguilar", "Alvarado", "Banegas", "Cáceres", "Cruz", "Díaz", "Flores", "Funes", "García", "Girón",
  "Hernández", "Interiano", "Jiménez", "Lagos", "López", "Martínez", "Mejía", "Mendoza", "Molina", "Ordóñez",
  "Pérez", "Quintanilla", "Ramos", "Reyes", "Rivera", "Sánchez", "Torres", "Urbina", "Valle", "Zelaya",
] as const;
const FEMALE_NAMES = ["Ana", "María", "Sofía", "Camila", "Valeria", "Daniela", "Gabriela", "Lucía", "Paola", "Andrea"] as const;
const MALE_NAMES = ["Luis", "Carlos", "José", "Mateo", "Diego", "Daniel", "Andrés", "Pedro", "Samuel", "Jorge"] as const;

/** The national id of the n-th student, when it has one: eight to twenty characters, with hyphens. */
export const seededDocument = (n: number): string => `0801-2012-${String(10000 + n)}`;
/** What a response shows of it: four asterisks and the last four characters. */
export const maskedDocument = (document: string): string => `****${document.replaceAll("-", "").slice(-4)}`;

export const emptyStudents = (): StudentRows => ({ students: [], documents: new Map() });

/** Sixty students ordered by their number: the first of a surname is a girl, the second a boy. */
export function seededStudents(): StudentRows {
  const students: StudentRow[] = [];
  const documents = new Map<string, string>();
  SURNAMES.forEach((lastName, index) => {
    for (const second of [false, true]) {
      const n = index * 2 + (second ? 2 : 1);
      const names = second ? MALE_NAMES : FEMALE_NAMES;
      const row: StudentRow = {
        id: studentId(n),
        studentCode: studentCodeOf(n),
        firstName: names[index % names.length] ?? "Ana",
        lastName,
        birthDate: `${String(2008 + (n % 12))}-${String(1 + (n % 12)).padStart(2, "0")}-${String(1 + (n % 27)).padStart(2, "0")}`,
        sex: second ? "M" : "F",
        status: n % 10 === 0 ? "INACTIVE" : "ACTIVE",
        createdAt: STAMP,
        updatedAt: STAMP,
      };
      students.push(row);
      if (n % 3 === 0) documents.set(row.id, seededDocument(n));
    }
  });
  return { students, documents };
}
