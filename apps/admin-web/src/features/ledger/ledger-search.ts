import { z } from "zod";
import { page, pageSize, sortOf } from "../../shared/resource-screen/list-search.ts";

/** The closed set of types of an account, in the order a chart is read. */
export const ACCOUNT_TYPES = ["ASSET", "LIABILITY", "EQUITY", "INCOME", "EXPENSE"] as const;

/**
 * The search parameters of the chart of accounts: the common ones (`page`, `pageSize`, `sort`) and the two filters the API
 * takes, `type` and `active`. A parameter that does not hold is dropped, so a bad URL never breaks the screen. The sort
 * fields are the ones the API accepts.
 */
export const ledgerAccountsSearch = z.object({
  page,
  pageSize,
  sort: sortOf(["code", "name"]),
  type: z.enum(ACCOUNT_TYPES).optional().catch(undefined),
  // A boolean: the router reads the address as JSON, so `active=false` arrives as `false`.
  active: z.boolean().optional().catch(undefined),
});

export type LedgerAccountsSearch = z.infer<typeof ledgerAccountsSearch>;
