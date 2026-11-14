import type { TableSort } from "@confia/ui";
import { z } from "zod";

/**
 * The search parameters of a listing, as docs/ui-ux/04 section 3.2 fixes them: `page` from 1, `pageSize`, and `sort`
 * as `field:asc|desc`. A parameter that does not hold is dropped and replaced by its default, so a bad URL never
 * breaks the screen. The API counts pages from 0 and takes `field,dir`; `toApiParams` does that change.
 */
export const DEFAULT_PAGE_SIZE = 25;

export const pageSize = z.union([z.literal(25), z.literal(50), z.literal(100)]).catch(DEFAULT_PAGE_SIZE);
export const page = z.number().int().min(1).catch(1);
const id = z.uuid().optional().catch(undefined);

export function sortOf(fields: readonly string[]) {
  return z
    .string()
    .refine((value) => {
      const [field = "", direction] = value.split(":");
      return fields.includes(field) && (direction === "asc" || direction === "desc");
    })
    .optional()
    .catch(undefined);
}

export const academicYearsSearch = z.object({ page, pageSize, sort: sortOf(["name", "startDate", "endDate", "status"]) });
export const modalitiesSearch = z.object({ page, pageSize, sort: sortOf(["code", "name"]) });
export const gradesSearch = z.object({ page, pageSize, sort: sortOf(["name", "ordinal"]), modalityId: id });
export const sectionsSearch = z.object({
  page,
  pageSize,
  sort: sortOf(["name", "capacity"]),
  academicYearId: id,
  gradeId: id,
});

export type AcademicYearsSearch = z.infer<typeof academicYearsSearch>;
export type ModalitiesSearch = z.infer<typeof modalitiesSearch>;
export type GradesSearch = z.infer<typeof gradesSearch>;
export type SectionsSearch = z.infer<typeof sectionsSearch>;

interface BaseSearch {
  readonly page: number;
  readonly pageSize: number;
  readonly sort?: string | undefined;
}

/** The order the table shows: the one in the URL as a column and a direction. */
export function tableSortOf(sort: string | undefined): TableSort | undefined {
  if (sort === undefined) return undefined;
  const [columnId = "", direction] = sort.split(":");
  return { columnId, direction: direction === "desc" ? "desc" : "asc" };
}

export function searchSortOf(sort: TableSort): string {
  return `${sort.columnId}:${sort.direction}`;
}

/** What the API takes: `page` from 0 and `sort` as `field,dir`; the filters go as they are, when there are any. */
export function toApiParams<S extends BaseSearch>(search: S): Omit<S, "page" | "pageSize" | "sort"> & { page: number; size: number; sort?: string } {
  const { page: current, pageSize: size, sort, ...filters } = search;
  return { ...filters, page: current - 1, size, ...(sort === undefined ? {} : { sort: sort.replace(":", ",") }) };
}

/** The search for the URL: a parameter that holds its default is not written (docs/ui-ux/04, section 3.2, rule 1). */
export function toUrlSearch(search: BaseSearch & Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(search).filter(
      ([name, value]) => value !== undefined && !(name === "page" && value === 1) && !(name === "pageSize" && value === DEFAULT_PAGE_SIZE),
    ),
  );
}
