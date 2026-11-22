import { z } from "zod";
import { page, pageSize, sortOf } from "../../shared/resource-screen/list-search.ts";

/** The states a payment is listed in, as the server derives them. */
export const PAYMENT_STATUSES = ["declared", "confirmed", "rejected"] as const;
/** The ways a payment is received. */
export const PAYMENT_METHODS = ["CASH", "BANK_TRANSFER"] as const;

const ISO_DAY = /^\d{4}-\d{2}-\d{2}$/;

/**
 * The payments list: the common paging, the order the API accepts (when it was registered, when it was received, or the amount) and its
 * filters: the status, the method and the days the money was received between. A part that does not hold is dropped, so a bad address
 * never breaks the screen.
 */
export const paymentsSearch = z.object({
  page,
  pageSize,
  sort: sortOf(["registeredAt", "receivedOn", "amount"]),
  status: z.enum(PAYMENT_STATUSES).optional().catch(undefined),
  method: z.enum(PAYMENT_METHODS).optional().catch(undefined),
  from: z.string().regex(ISO_DAY).optional().catch(undefined),
  to: z.string().regex(ISO_DAY).optional().catch(undefined),
});

export type PaymentsSearch = z.infer<typeof paymentsSearch>;
