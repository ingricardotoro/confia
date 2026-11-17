/** The path of the charges area. It has one view today, the plans, and the area redirects to it. */
export const CHARGES_PATH = "/charges";

export const CHARGES_PATHS = {
  plans: `${CHARGES_PATH}/plans`,
} as const;

/** The address of one plan. The id is encoded, so it can never change the path. */
export const chargePlanPath = (id: string): string => `${CHARGES_PATHS.plans}/${encodeURIComponent(id)}`;
