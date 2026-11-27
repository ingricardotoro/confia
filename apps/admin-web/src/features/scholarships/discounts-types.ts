import type { AssignmentResponseOutput, DiscountResponseOutput } from "@confia/contracts/admin";

// What the discount views read. It comes from the generated contracts, not from shared/api: a presentation component never imports the API client
// module, not even for a type (admin-presentation-is-pure).
export type Discount = DiscountResponseOutput;
export type DiscountKind = DiscountResponseOutput["kind"];
export type DiscountAssignment = AssignmentResponseOutput;

/** The discount an assignment points at, as the assignment names it: the code and the name, both optional in the contract. */
export const discountLabelOf = (assignment: Pick<DiscountAssignment, "discountCode" | "discountName">): string | undefined =>
  assignment.discountCode == null ? undefined : assignment.discountName == null ? assignment.discountCode : `${assignment.discountCode} · ${assignment.discountName}`;

/** What a discount is called in a select and in a sentence: its code and its name. */
export const discountOptionOf = (discount: Pick<Discount, "code" | "name">): string => `${discount.code} · ${discount.name}`;
