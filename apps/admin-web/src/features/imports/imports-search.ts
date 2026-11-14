import { z } from "zod";
import { page, pageSize } from "../organization/list-search.ts";

/**
 * The search parameters of the history of batches: `page` and `pageSize`, as every listing has them. The API offers no order
 * and no filter for batches (newest first), so there are no more. A parameter that does not hold is dropped.
 */
export const importsSearch = z.object({ page, pageSize });

export type ImportsSearch = z.infer<typeof importsSearch>;
