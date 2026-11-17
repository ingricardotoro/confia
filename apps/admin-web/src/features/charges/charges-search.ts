import { z } from "zod";
import { optionalId, page, pageSize, sortOf } from "../../shared/resource-screen/list-search.ts";

/** The scopes of a plan, from the widest to the narrowest. */
export const CHARGE_PLAN_SCOPES = ["MODALITY", "GRADE", "SECTION"] as const;
/** The states of a plan, in the order it goes through them. */
export const CHARGE_PLAN_STATUSES = ["DRAFT", "ACTIVE", "ARCHIVED"] as const;
/** How often a plan accrues, from the most frequent. */
export const CHARGE_PLAN_FREQUENCIES = ["MONTHLY", "BIMONTHLY", "ANNUAL", "ONE_TIME"] as const;

/**
 * The search of the plans: the common parameters, the three filters the API takes (academic year, scope and status)
 * and the sort fields it accepts. A parameter that does not hold is dropped, so a bad URL never breaks the screen.
 */
export const chargePlansSearch = z.object({
  page,
  pageSize,
  sort: sortOf(["name", "createdAt"]),
  academicYearId: optionalId,
  targetScope: z.enum(CHARGE_PLAN_SCOPES).optional().catch(undefined),
  status: z.enum(CHARGE_PLAN_STATUSES).optional().catch(undefined),
});

export type ChargePlansSearch = z.infer<typeof chargePlansSearch>;

const ISO_DAY = /^\d{4}-\d{2}-\d{2}$/;

/** The plan and the period of the generation page, so a link opens it already pointed at what to generate. */
export const accrualSearch = z.object({
  planId: optionalId,
  periodStart: z.string().regex(ISO_DAY).optional().catch(undefined),
});

export type AccrualSearch = z.infer<typeof accrualSearch>;

/** The runs list: the common paging and the one filter the API takes, the plan. The API orders runs by itself, newest first. */
export const accrualRunsSearch = z.object({
  page,
  pageSize,
  planId: optionalId,
});

export type AccrualRunsSearch = z.infer<typeof accrualRunsSearch>;

/** The page of items of one run. */
export const accrualRunSearch = z.object({
  page,
  pageSize,
});

export type AccrualRunSearch = z.infer<typeof accrualRunSearch>;
