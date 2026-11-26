import type { ChargeSimulationResponseOutput } from "@confia/contracts/admin";
import type { Award, Scholarship } from "../../shared/api/scholarships-operations.ts";

export type { Award, AwardStatus, BenefitType, Scholarship } from "../../shared/api/scholarships-operations.ts";

// The simulation the simulator view reads. It comes from the generated contracts, not from shared/api: a presentation
// component never imports the API client module, not even for a type (admin-presentation-is-pure).
export type ChargeSimulation = ChargeSimulationResponseOutput;
export type ChargeSimulationLine = ChargeSimulationResponseOutput["lines"][number];

/** The scholarship an award points at, as the award names it: the code and the name, both optional in the contract. */
export const scholarshipLabelOf = (award: Pick<Award, "scholarshipCode" | "scholarshipName">): string | undefined =>
  award.scholarshipCode == null ? undefined : award.scholarshipName == null ? award.scholarshipCode : `${award.scholarshipCode} · ${award.scholarshipName}`;

/** What a scholarship is called in a select and in a sentence: its code and its name. */
export const scholarshipOptionOf = (scholarship: Pick<Scholarship, "code" | "name">): string => `${scholarship.code} · ${scholarship.name}`;
