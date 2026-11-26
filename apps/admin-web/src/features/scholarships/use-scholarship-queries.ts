import { keepPreviousData, useMutation, useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";
import { ApiFailure } from "../../shared/api/api-failure.ts";
import { useAuthorizedSend } from "../../shared/api/authorized-send.tsx";
import {
  deactivateScholarship,
  listScholarships,
  reactivateScholarship,
  type Scholarship,
  type ScholarshipListParams,
} from "../../shared/api/scholarships-operations.ts";
import { LOOKUP_PAGE_SIZE } from "../../shared/organization-lookups/use-organization-queries.ts";
import { awardKeys, scholarshipKeys } from "./scholarships-keys.ts";
import { writeScholarship, type ScholarshipSave } from "./scholarship-form.ts";

/** A listing of scholarships, as the server pages, orders and filters it. The page on screen stays until the next arrives. */
export function useScholarshipList(params: ScholarshipListParams) {
  const send = useAuthorizedSend();
  return useQuery({
    queryKey: scholarshipKeys.list(params),
    queryFn: () => send((token) => listScholarships(token, params)),
    placeholderData: keepPreviousData,
  });
}

/**
 * The scholarships an award can name, for a select or a filter: one page of the largest size the API gives, in the order of the name. A lookup is a
 * label and an option, never a filter of a table. `activeOnly` leaves out the ones that are no longer offered (a new award needs an active one).
 */
export function useScholarshipLookup(activeOnly: boolean) {
  const list = useScholarshipList({ page: 0, size: LOOKUP_PAGE_SIZE, sort: "name,asc", ...(activeOnly ? { active: true } : {}) });
  return { rows: list.data?.items ?? [], failed: list.isError, loading: list.isPending, retry: () => void list.refetch() };
}

/** The 409 code that says what is on screen is no longer what the server holds. */
const STALE_CODES: ReadonlySet<string> = new Set(["stale-version"]);

function refreshOnConflict(queryClient: QueryClient, error: unknown): void {
  if (!(error instanceof ApiFailure) || error.result.kind !== "problem" || !STALE_CODES.has(error.result.problem.code)) return;
  void queryClient.invalidateQueries({ queryKey: scholarshipKeys.all });
}

/**
 * What every write of a scholarship has in common. It books nothing in the ledger (a scholarship only changes the charges to come), so nothing is
 * optimistic and no `Idempotency-Key` is sent, but it never repeats by itself. After a write the scholarships are read again, and so are the awards
 * (they show the code and the name of their scholarship). A simulation is never cached, so there is none to invalidate.
 */
function useScholarshipWrite<Variables>(operation: (token: string, variables: Variables) => ReturnType<typeof writeScholarship>) {
  const send = useAuthorizedSend();
  const queryClient = useQueryClient();
  return useMutation<Scholarship, Error, Variables>({
    mutationFn: (variables) => send((token) => operation(token, variables)),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: scholarshipKeys.all }),
        queryClient.invalidateQueries({ queryKey: awardKeys.all }),
      ]);
    },
    onError: (error) => {
      refreshOnConflict(queryClient, error);
    },
  });
}

/** The creation or the edit of a scholarship. */
export const useScholarshipSave = () => useScholarshipWrite<ScholarshipSave>((token, save) => writeScholarship(token, save));

export interface ScholarshipToggle {
  readonly id: string;
  readonly active: boolean;
}

/** Deactivates or reactivates a scholarship: never a delete. A deactivated scholarship stops applying to the charges to come. */
export const useScholarshipToggle = () =>
  useScholarshipWrite<ScholarshipToggle>((token, { id, active }) => (active ? reactivateScholarship(token, id) : deactivateScholarship(token, id)));
