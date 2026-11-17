import type { Option } from "../../shared/resource-screen/components/fields.tsx";

interface Named {
  readonly id: string;
  readonly name: string;
}

interface SectionRow extends Named {
  readonly academicYearId: string;
  readonly gradeId: string;
}

/** How a section is written for a person: its grade, its name and its year. The catalog says it in words, so this takes the words. */
export type SectionLabel = (parts: { readonly grade: string; readonly section: string; readonly year: string }) => string;

/**
 * The name of every section, as `Primero A (Año lectivo 2026)`: a section is named "A" in many grades and years, so its
 * own name says nothing. A grade or a year that is not among the ones loaded is `unknown`. It only joins what the server
 * sent, in the order it sent it.
 */
export function sectionNamesOf(
  sections: readonly SectionRow[],
  grades: readonly Named[],
  years: readonly Named[],
  label: SectionLabel,
  unknown: string,
): ReadonlyMap<string, string> {
  const gradeNames = new Map(grades.map((grade) => [grade.id, grade.name]));
  const yearNames = new Map(years.map((year) => [year.id, year.name]));
  return new Map(
    sections.map((section) => [
      section.id,
      label({ grade: gradeNames.get(section.gradeId) ?? unknown, section: section.name, year: yearNames.get(section.academicYearId) ?? unknown }),
    ]),
  );
}

/** The names of what a plan can point to: modalities, grades and sections, by id (the ids are unique across the three). */
export function referenceNamesOf(modalities: readonly Named[], grades: readonly Named[], sectionNames: ReadonlyMap<string, string>): ReadonlyMap<string, string> {
  return new Map([...modalities.map((row) => [row.id, row.name] as const), ...grades.map((row) => [row.id, row.name] as const), ...sectionNames]);
}

/** The options of the section select, in the order the server listed the sections. */
export const sectionOptionsOf = (sections: readonly { readonly id: string }[], names: ReadonlyMap<string, string>): Option[] =>
  sections.map((section) => ({ value: section.id, label: names.get(section.id) ?? section.id }));
