/**
 * The address of the charges of a student. It lives in `shared` for the same reason as the statement's: the charges own the screen
 * and the students registry links to it, and a feature never imports another.
 */
export const STUDENT_CHARGES_PATH = "/charges/student-charges";

/** The charges of one student. The id is encoded, so it can never change the address. */
export const studentChargesPath = (studentId: string): string => `${STUDENT_CHARGES_PATH}?studentId=${encodeURIComponent(studentId)}`;
