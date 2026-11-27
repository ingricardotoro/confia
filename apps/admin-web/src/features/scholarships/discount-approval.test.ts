import { describe, expect, it } from "vitest";
import { discount, discountProposal } from "../../test/contract-responses.ts";
import { approvalStateOf, authorOfWaiting, awaitsDecision, isFrozen, mayOfferDecision } from "./discount-approval.ts";

// What a discount waits for (S7), read from the fields the server sends: the interface only decides what to show, and the server refuses the rest.
const AUTHOR = "00000000-0000-4000-8000-0000000000a1";
const OTHER = "00000000-0000-4000-8000-0000000000a2";
const pending = discount({ approvalStatus: "pending", createdBy: AUTHOR });
const proposal = discount({ pendingChange: discountProposal({ requestedBy: AUTHOR }), createdBy: OTHER });
const rejected = discount({ approvalStatus: "rejected", createdBy: AUTHOR });

describe("approvalStateOf", () => {
  it("tells a campaign discount waiting for its creation, one with a proposal, a rejected one and an approved one", () => {
    expect(approvalStateOf(pending)).toBe("pending");
    expect(approvalStateOf(proposal)).toBe("proposal");
    expect(approvalStateOf(rejected)).toBe("rejected");
    expect(approvalStateOf(discount())).toBe("approved");
  });
});

describe("isFrozen", () => {
  it("is true for everything that is not approved without a proposal: no edit and no reactivation, only deactivating", () => {
    expect([pending, proposal, rejected].map(isFrozen)).toEqual([true, true, true]);
    expect(isFrozen(discount())).toBe(false);
  });
});

describe("who decides", () => {
  it("has something to decide only a pending discount or a proposal", () => {
    expect([pending, proposal, rejected, discount()].map(awaitsDecision)).toEqual([true, true, false, false]);
  });

  it("names the creator of a pending discount and the proposer of a proposal as the ones who cannot decide", () => {
    expect(authorOfWaiting(pending)).toBe(AUTHOR);
    expect(authorOfWaiting(proposal)).toBe(AUTHOR);
    expect(authorOfWaiting(discount())).toBeUndefined();
  });

  it("does not offer the decision to the creator or the proposer, offers it to anyone else, and offers it when the session does not say who the person is", () => {
    expect(mayOfferDecision(pending, AUTHOR)).toBe(false);
    expect(mayOfferDecision(proposal, AUTHOR)).toBe(false);
    expect(mayOfferDecision(pending, OTHER)).toBe(true);
    expect(mayOfferDecision(proposal, OTHER)).toBe(true);
    expect(mayOfferDecision(pending, undefined)).toBe(true);
    expect(mayOfferDecision(rejected, OTHER)).toBe(false);
    expect(mayOfferDecision(discount(), OTHER)).toBe(false);
  });

  it("looks at who proposed, not who created, when the discount has a proposal", () => {
    expect(mayOfferDecision(proposal, OTHER)).toBe(true);
    expect(mayOfferDecision({ ...proposal, createdBy: OTHER }, OTHER)).toBe(true);
  });
});
