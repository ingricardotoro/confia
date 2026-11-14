import { nameMapOf, useGradeLookup, useModalityLookup } from "../organization/use-lookups.ts";
import type { Option } from "../organization/components/fields.tsx";

export interface GradeLabels {
  /** Grade id to "Modalidad · Grado": the same grade name can exist in two modalities. */
  readonly labels: ReadonlyMap<string, string>;
  /** The active grades, as the options of a select, labelled the same way. */
  readonly options: readonly Option[];
  readonly failed: boolean;
  readonly retry: () => void;
}

/** The grades told with their modality, for the grade filter and for the groups of the section selects. */
export function useGradeLabels(): GradeLabels {
  const grades = useGradeLookup();
  const modalities = useModalityLookup();
  const modalityNames = nameMapOf(modalities.rows);
  const named = grades.rows.map((grade) => {
    const modality = modalityNames.get(grade.modalityId);
    return { id: grade.id, label: modality === undefined ? grade.name : `${modality} · ${grade.name}`, isActive: grade.isActive };
  });
  return {
    labels: new Map(named.map((grade) => [grade.id, grade.label])),
    options: named.filter((grade) => grade.isActive).map((grade) => ({ value: grade.id, label: grade.label })),
    failed: grades.failed || modalities.failed,
    retry: () => {
      if (grades.failed) grades.retry();
      if (modalities.failed) modalities.retry();
    },
  };
}
