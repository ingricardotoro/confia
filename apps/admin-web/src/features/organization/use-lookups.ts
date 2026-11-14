import { listAcademicYears, listGrades, listModalities } from "../../shared/api/organization-operations.ts";
import type { Option } from "./components/fields.tsx";
import { LOOKUP_PAGE_SIZE, useResourceList } from "./use-organization-queries.ts";

/**
 * The lists a screen reads to name what a row points to and to fill a select: modalities, years, grades. They are
 * ordinary server lists of the largest page the API gives, so the choices are as complete as one page can be;
 * their sort is the server's. A lookup is a label and an option, never a filter of the table.
 */
export interface Lookup<Row> {
  readonly rows: readonly Row[];
  readonly failed: boolean;
  /** The first load is still under way. */
  readonly loading: boolean;
  readonly retry: () => void;
}

const FIRST_PAGE = { page: 0, size: LOOKUP_PAGE_SIZE };

export function useModalityLookup(): Lookup<{ id: string; code: string; name: string; isActive: boolean }> {
  const list = useResourceList("modalities", { ...FIRST_PAGE, sort: "name,asc" }, listModalities);
  return { rows: list.data?.items ?? [], failed: list.isError, loading: list.isPending, retry: () => void list.refetch() };
}

export function useYearLookup(): Lookup<{ id: string; name: string; status: "PLANNED" | "ACTIVE" | "CLOSED"; isActive: boolean }> {
  const list = useResourceList("academic-years", { ...FIRST_PAGE, sort: "startDate,desc" }, listAcademicYears);
  return { rows: list.data?.items ?? [], failed: list.isError, loading: list.isPending, retry: () => void list.refetch() };
}

export function useGradeLookup(): Lookup<{ id: string; modalityId: string; name: string; isActive: boolean }> {
  const list = useResourceList("grades", { ...FIRST_PAGE, sort: "name,asc" }, listGrades);
  return { rows: list.data?.items ?? [], failed: list.isError, loading: list.isPending, retry: () => void list.refetch() };
}

export const nameMapOf = (rows: readonly { readonly id: string; readonly name: string }[]): ReadonlyMap<string, string> =>
  new Map(rows.map((row) => [row.id, row.name]));

export const optionsOf = (rows: readonly { readonly id: string; readonly name: string }[]): Option[] =>
  rows.map((row) => ({ value: row.id, label: row.name }));
