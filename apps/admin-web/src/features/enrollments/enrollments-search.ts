import { z } from "zod";
import { page, pageSize, sortOf } from "../organization/list-search.ts";

export const ENROLLMENT_STATUSES = ["ACTIVE", "WITHDRAWN", "TRANSFERRED", "GRADUATED"] as const;

const id = z.uuid().optional().catch(undefined);

/**
 * The search parameters of the enrollments list: the common ones (`page`, `pageSize`, `sort`) and the filters. A
 * parameter that does not hold is dropped, so a bad URL never breaks the screen. An absent `academicYearId` is not
 * "no year": the server then uses the year in progress, and so does the screen. The sort fields are the ones the API accepts.
 */
export const enrollmentsSearch = z.object({
  page,
  pageSize,
  sort: sortOf(["enrolledOn", "studentLastName", "status"]),
  academicYearId: id,
  gradeId: id,
  sectionId: id,
  status: z.enum(ENROLLMENT_STATUSES).optional().catch(undefined),
});

export type EnrollmentsSearch = z.infer<typeof enrollmentsSearch>;
