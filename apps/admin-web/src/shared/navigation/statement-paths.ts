/**
 * The address of the statement of account of a student. It lives in `shared` because two areas name it: the charges, which own
 * the screen, and the students registry, which links to it. A feature never imports another, so the path is the one thing both
 * may share.
 */
export const STATEMENT_PATH = "/charges/statement";

/** The statement of one student. The id is encoded, so it can never change the address. */
export const studentStatementPath = (studentId: string): string => `${STATEMENT_PATH}?studentId=${encodeURIComponent(studentId)}`;
