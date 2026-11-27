import { z } from "zod";
import { optionalId, page, pageSize, sortOf } from "../../shared/resource-screen/list-search.ts";
import { AWARD_STATUSES, BENEFIT_TYPES } from "./scholarships-search.ts";

/** The classes of a discount: a campaign applies by itself, an agreement only to the students with an approved assignment. */
export const DISCOUNT_KINDS = ["campaign", "agreement"] as const;

/**
 * The discounts list: the common paging, the order the API accepts (the code, the name, where the validity begins or when it was created) and its
 * three filters, the class, the type of benefit and whether it is active. A part that does not hold is dropped, so a bad address never breaks the screen.
 */
export const discountsSearch = z.object({
  page,
  pageSize,
  sort: sortOf(["code", "name", "validFrom", "createdAt"]),
  kind: z.enum(DISCOUNT_KINDS).optional().catch(undefined),
  benefitType: z.enum(BENEFIT_TYPES).optional().catch(undefined),
  // A boolean: the router reads the address as JSON, so `active=false` arrives as `false`.
  active: z.boolean().optional().catch(undefined),
});

export type DiscountsSearch = z.infer<typeof discountsSearch>;

/**
 * The agreement assignments list: the common paging, the order the API accepts (when it was requested, or where its validity begins or ends) and its
 * three filters, the status, the discount and the student (so a link opens it already pointed at one).
 */
export const assignmentsSearch = z.object({
  page,
  pageSize,
  sort: sortOf(["requestedAt", "validFrom", "validTo"]),
  status: z.enum(AWARD_STATUSES).optional().catch(undefined),
  discountId: optionalId,
  studentId: optionalId,
});

export type AssignmentsSearch = z.infer<typeof assignmentsSearch>;
