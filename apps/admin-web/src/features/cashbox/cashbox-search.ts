import { z } from "zod";
import { optionalId, page, pageSize, sortOf } from "../../shared/resource-screen/list-search.ts";

/** The states a session is listed in, as the server derives them. */
export const CASH_SESSION_STATUSES = ["open", "pending_approval", "closed"] as const;

const ISO_DAY = /^\d{4}-\d{2}-\d{2}$/;

/**
 * The sessions list: the common paging, the order the API accepts (when it was opened, or its status) and its filters: the status, the
 * cashier (so a link from another screen opens it already pointed at one) and the days it was opened between. A part that does not
 * hold is dropped, so a bad address never breaks the screen.
 */
export const cashSessionsSearch = z.object({
  page,
  pageSize,
  sort: sortOf(["openedAt", "status"]),
  status: z.enum(CASH_SESSION_STATUSES).optional().catch(undefined),
  cashier: optionalId,
  from: z.string().regex(ISO_DAY).optional().catch(undefined),
  to: z.string().regex(ISO_DAY).optional().catch(undefined),
});

export type CashSessionsSearch = z.infer<typeof cashSessionsSearch>;
