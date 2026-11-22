// The reads of the ledger that a payment changes. They live in `shared` because the charges own the screens and the payments invalidate them
// after a write, and a feature never imports another.

/**
 * Query keys of the statement of account, general to specific: every statement, one student's statements, one statement with
 * its currency, period and page. A write that moves money (a charge run, later a payment) invalidates the `all` prefix.
 */
export const statementKeys = {
  all: ["charges", "statement"] as const,
  student: (studentId: string) => [...statementKeys.all, studentId] as const,
  detail: (studentId: string, params: object) => [...statementKeys.student(studentId), params] as const,
};

/**
 * Query keys of the charges of a student, general to specific: every student's charges, one student's, one list with its filters,
 * order and page. A void invalidates the `all` prefix, with the statements.
 */
export const studentChargeKeys = {
  all: ["charges", "student-charges"] as const,
  student: (studentId: string) => [...studentChargeKeys.all, studentId] as const,
  list: (studentId: string, params: object) => [...studentChargeKeys.student(studentId), "list", params] as const,
};
