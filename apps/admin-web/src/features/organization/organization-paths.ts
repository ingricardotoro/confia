import type { AdminKey } from "../../i18n/resources.ts";

/** The path of the academic structure area. Its four views live below it. */
export const ORGANIZATION_PATH = "/organization";

/** The path of each view, so a route and a link name it the same way. */
export const ORGANIZATION_PATHS = {
  academicYears: `${ORGANIZATION_PATH}/academic-years`,
  modalities: `${ORGANIZATION_PATH}/modalities`,
  grades: `${ORGANIZATION_PATH}/grades`,
  sections: `${ORGANIZATION_PATH}/sections`,
} as const;

export interface OrganizationView {
  readonly id: "academic-years" | "modalities" | "grades" | "sections";
  readonly path: (typeof ORGANIZATION_PATHS)[keyof typeof ORGANIZATION_PATHS];
  readonly labelKey: AdminKey;
}

/** The views of the area, in the order of its sub navigation. */
export const ORGANIZATION_VIEWS: readonly OrganizationView[] = [
  { id: "academic-years", path: ORGANIZATION_PATHS.academicYears, labelKey: "organization.tabs.academicYears" },
  { id: "modalities", path: ORGANIZATION_PATHS.modalities, labelKey: "organization.tabs.modalities" },
  { id: "grades", path: ORGANIZATION_PATHS.grades, labelKey: "organization.tabs.grades" },
  { id: "sections", path: ORGANIZATION_PATHS.sections, labelKey: "organization.tabs.sections" },
];
