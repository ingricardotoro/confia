/**
 * The address of the awards of scholarships. It lives in `shared` for the same reason as the statement's: the scholarships own the screen
 * and the students registry links to it, and a feature never imports another.
 */
export const SCHOLARSHIP_AWARDS_PATH = "/scholarships/awards";

/** The awards of one student. The id is encoded, so it can never change the address. */
export const studentAwardsPath = (studentId: string): string => `${SCHOLARSHIP_AWARDS_PATH}?studentId=${encodeURIComponent(studentId)}`;
