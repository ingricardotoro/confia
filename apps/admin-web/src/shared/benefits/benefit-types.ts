import type { AppliedBenefitOutput } from "@confia/contracts/admin";

/**
 * A benefit that reduced a charge, as the server gives it: where it came from, its code as it was when the charge was issued, and the amount it took
 * off. It comes from the generated contracts, not from shared/api: a presentation component never imports the API client module, not even for a type
 * (admin-presentation-is-pure).
 */
export type AppliedBenefit = AppliedBenefitOutput;
