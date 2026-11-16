import { z } from "zod";
import { optionalId, page, pageSize, sortOf } from "../../shared/resource-screen/list-search.ts";

/** The search parameters of the four views of the academic structure, each with the fields its server listing orders by. */
export const academicYearsSearch = z.object({ page, pageSize, sort: sortOf(["name", "startDate", "endDate", "status"]) });
export const modalitiesSearch = z.object({ page, pageSize, sort: sortOf(["code", "name"]) });
export const gradesSearch = z.object({ page, pageSize, sort: sortOf(["name", "ordinal"]), modalityId: optionalId });
export const sectionsSearch = z.object({
  page,
  pageSize,
  sort: sortOf(["name", "capacity"]),
  academicYearId: optionalId,
  gradeId: optionalId,
});

export type AcademicYearsSearch = z.infer<typeof academicYearsSearch>;
export type ModalitiesSearch = z.infer<typeof modalitiesSearch>;
export type GradesSearch = z.infer<typeof gradesSearch>;
export type SectionsSearch = z.infer<typeof sectionsSearch>;
