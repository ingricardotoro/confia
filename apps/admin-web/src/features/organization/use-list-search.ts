import { useNavigate, useSearch } from "@tanstack/react-router";
import { toUrlSearch } from "./list-search.ts";
import type { SearchChange } from "./search-change.ts";

/**
 * The search of a listing and the way to change it. The state of the table lives in the address (docs/ui-ux/04,
 * section 3.2): a link shows the same page, order and filters to whoever opens it, and the default values are not
 * written. The router has already validated the search; reading it through the schema again gives its type.
 */
export function useListSearch<S extends { page: number; pageSize: number; sort?: string | undefined }>(parse: (raw: unknown) => S): [S, SearchChange<S>] {
  const raw: unknown = useSearch({ strict: false });
  const navigate = useNavigate();
  return [
    parse(raw),
    (next, options) => {
      void navigate({ search: toUrlSearch(next) as never, replace: options?.replace ?? false });
    },
  ];
}
