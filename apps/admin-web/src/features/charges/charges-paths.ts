/** The path of the charges area. It has the plans and the accrual of charges, and the area redirects to the plans. */
export const CHARGES_PATH = "/charges";

export const CHARGES_PATHS = {
  plans: `${CHARGES_PATH}/plans`,
  accrual: `${CHARGES_PATH}/accrual`,
  accrualRuns: `${CHARGES_PATH}/accrual/runs`,
} as const;

/** The address of one plan. The id is encoded, so it can never change the path. */
export const chargePlanPath = (id: string): string => `${CHARGES_PATHS.plans}/${encodeURIComponent(id)}`;

/** The address of one accrual run. The id is encoded, so it can never change the path. */
export const accrualRunPath = (id: string): string => `${CHARGES_PATHS.accrualRuns}/${encodeURIComponent(id)}`;
