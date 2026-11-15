import { z } from "zod";
import { page, pageSize } from "../organization/list-search.ts";
import { IMPORT_KINDS } from "./import-model.ts";

/**
 * The search parameters of the history of batches: `page` and `pageSize`, as every listing has them, and `kind`, the one filter
 * the API offers (students, guardians, guardian-links or enrollments; absent means every kind). There is no order: the history is newest
 * first. `actsPage` is the page of the actas of the reconciliation (ten at a time, also newest first), so a link shows the same
 * page of each. A parameter that does not hold is dropped.
 */
export const importsSearch = z.object({ page, pageSize, kind: z.enum(IMPORT_KINDS).optional().catch(undefined), actsPage: page });

export type ImportsSearch = z.infer<typeof importsSearch>;
