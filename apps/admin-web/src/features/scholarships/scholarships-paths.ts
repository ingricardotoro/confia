import { SCHOLARSHIP_AWARDS_PATH } from "../../shared/navigation/scholarship-paths.ts";

/** The path of the scholarships area. It has the scholarships, the awards and the simulator of charges, and the area redirects to the scholarships. */
export const SCHOLARSHIPS_PATH = "/scholarships";

export const SCHOLARSHIPS_PATHS = {
  list: `${SCHOLARSHIPS_PATH}/list`,
  awards: SCHOLARSHIP_AWARDS_PATH,
  simulator: `${SCHOLARSHIPS_PATH}/simulator`,
} as const;

/** The address of one award. The id is encoded, so it can never change the path. */
export const awardPath = (id: string): string => `${SCHOLARSHIPS_PATHS.awards}/${encodeURIComponent(id)}`;

/** The discounts and the agreement assignments of the area. */
export const DISCOUNT_PATHS = {
  discounts: `${SCHOLARSHIPS_PATH}/discounts`,
  assignments: `${SCHOLARSHIPS_PATH}/discount-assignments`,
} as const;

/** The address of one agreement assignment. The id is encoded, so it can never change the path. */
export const assignmentPath = (id: string): string => `${DISCOUNT_PATHS.assignments}/${encodeURIComponent(id)}`;
