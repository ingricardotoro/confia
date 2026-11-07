import { join } from "node:path";

/** Where the gate leaves its evidence; the CI job uploads `reports/` as `admin-web-e2e-report` (design.md D16). */
export const A11Y_REPORT_DIR = join(import.meta.dirname, "..", "..", "reports", "a11y");
export const A11Y_RESULTS_DIR = join(A11Y_REPORT_DIR, "results");
export const A11Y_REPORT_FILE = join(A11Y_REPORT_DIR, "report.json");
