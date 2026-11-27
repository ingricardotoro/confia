import type { Discount } from "./discounts-types.ts";

/**
 * What a discount waits for, as the server reports it (S7): `pending` is a campaign discount nobody else approved yet (it applies to nothing),
 * `proposal` is an approved one with a proposal to widen or reactivate it (the terms in force keep applying), `rejected` is a campaign discount another
 * person turned down, and `approved` waits for nothing. An agreement discount is always approved.
 */
export type DiscountApprovalState = "approved" | "pending" | "proposal" | "rejected";

export const approvalStateOf = (discount: Pick<Discount, "approvalStatus" | "pendingChange">): DiscountApprovalState => {
  if (discount.approvalStatus === "pending") return "pending";
  if (discount.approvalStatus === "rejected") return "rejected";
  return discount.pendingChange === undefined ? "approved" : "proposal";
};

/** The discount cannot be edited or reactivated while it waits or after it was rejected (409 `discount-pending-approval`); it can still be deactivated. */
export const isFrozen = (discount: Pick<Discount, "approvalStatus" | "pendingChange">): boolean => approvalStateOf(discount) !== "approved";

/** Whether another person has something to decide on the discount: a campaign discount that is pending, or one with a proposal. */
export const awaitsDecision = (discount: Pick<Discount, "approvalStatus" | "pendingChange">): boolean => {
  const state = approvalStateOf(discount);
  return state === "pending" || state === "proposal";
};

/**
 * Who has to be someone else for the decision: whoever created the discount while it is pending, whoever proposed the change while it is a proposal.
 * Display only: the server refuses that person (403 `self-approval-forbidden`), in the use case and in the database.
 */
export const authorOfWaiting = (discount: Pick<Discount, "approvalStatus" | "pendingChange" | "createdBy">): string | undefined => {
  const state = approvalStateOf(discount);
  if (state === "pending") return discount.createdBy;
  return state === "proposal" ? discount.pendingChange?.requestedBy : undefined;
};

/** Whether the interface offers Approve and Reject to this account: something waits, and it was not the account that put it there. */
export const mayOfferDecision = (discount: Pick<Discount, "approvalStatus" | "pendingChange" | "createdBy">, accountId: string | undefined): boolean =>
  awaitsDecision(discount) && (accountId === undefined || authorOfWaiting(discount) !== accountId);
