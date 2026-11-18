/**
 * Query keys of the charges plans, general to specific, so a prefix invalidates what it covers: every plan, every
 * listing, one listing, every detail, one detail. Nothing writes a literal key inline.
 */
export const chargePlanKeys = {
  all: ["charges", "plans"] as const,
  lists: () => [...chargePlanKeys.all, "list"] as const,
  list: (params: object) => [...chargePlanKeys.lists(), params] as const,
  details: () => [...chargePlanKeys.all, "detail"] as const,
  detail: (id: string) => [...chargePlanKeys.details(), id] as const,
};

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
 * Query keys of the accrual engine, general to specific, so a prefix invalidates what it covers: everything the engine
 * shows, the periods of one plan, every list of runs, one list, every run, one run with the page of its items. The preview
 * is a request that writes nothing and is not cached, so it has no key.
 */
export const accrualKeys = {
  all: ["charges", "accrual"] as const,
  periods: (planId: string) => [...accrualKeys.all, "periods", planId] as const,
  runs: () => [...accrualKeys.all, "runs"] as const,
  runLists: () => [...accrualKeys.runs(), "list"] as const,
  runList: (params: object) => [...accrualKeys.runLists(), params] as const,
  runDetails: () => [...accrualKeys.runs(), "detail"] as const,
  runDetail: (id: string, params: object) => [...accrualKeys.runDetails(), id, params] as const,
};
