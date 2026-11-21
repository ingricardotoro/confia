import type { AllocationResponseOutput, PaymentResponseOutput, PaymentSummaryResponseOutput, StudentChargeResponseOutput } from "@confia/contracts/admin";

// Types the presentation components of this feature read. They come from the generated contracts, not from shared/api: a presentation
// component never imports the API client module, not even for a type (admin-presentation-is-pure).
export type Payment = PaymentResponseOutput;
export type PaymentRow = PaymentSummaryResponseOutput;
export type PaymentStatus = PaymentResponseOutput["status"];
export type PaymentMethod = PaymentResponseOutput["method"];
export type Allocation = AllocationResponseOutput;
export type PayableCharge = StudentChargeResponseOutput;
