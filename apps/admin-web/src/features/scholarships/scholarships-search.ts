import { z } from "zod";
import { optionalId, page, pageSize, sortOf } from "../../shared/resource-screen/list-search.ts";

/** The types of benefit of a scholarship. */
export const BENEFIT_TYPES = ["PERCENTAGE", "FIXED_AMOUNT"] as const;
/** The states of an award, in the order it goes through them. */
export const AWARD_STATUSES = ["pending", "approved", "rejected", "revoked"] as const;

/**
 * The scholarships list: the common paging, the order the API accepts (the code, the name or when it was created) and its two filters, the type
 * of benefit and whether it is active. A part that does not hold is dropped, so a bad address never breaks the screen.
 */
export const scholarshipsSearch = z.object({
  page,
  pageSize,
  sort: sortOf(["code", "name", "createdAt"]),
  benefitType: z.enum(BENEFIT_TYPES).optional().catch(undefined),
  // A boolean: the router reads the address as JSON, so `active=false` arrives as `false`.
  active: z.boolean().optional().catch(undefined),
});

export type ScholarshipsSearch = z.infer<typeof scholarshipsSearch>;

/**
 * The awards list: the common paging, the order the API accepts (when it was requested, or where its validity begins or ends) and its three
 * filters, the status, the scholarship and the student (so a link from the students registry opens it already pointed at one).
 */
export const awardsSearch = z.object({
  page,
  pageSize,
  sort: sortOf(["requestedAt", "validFrom", "validTo"]),
  status: z.enum(AWARD_STATUSES).optional().catch(undefined),
  scholarshipId: optionalId,
  studentId: optionalId,
});

export type AwardsSearch = z.infer<typeof awardsSearch>;

const ISO_DAY = /^\d{4}-\d{2}-\d{2}$/;

/** The simulator: the plan, the student and the first day of the period, so a link opens it already pointed at what to simulate. */
export const simulatorSearch = z.object({
  planId: optionalId,
  studentId: optionalId,
  period: z.string().regex(ISO_DAY).optional().catch(undefined),
});

export type SimulatorSearch = z.infer<typeof simulatorSearch>;
