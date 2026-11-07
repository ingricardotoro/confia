import { existsSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { A11Y_REPORT_FILE, A11Y_RESULTS_DIR } from "./a11y-paths.ts";
import { summarizeReport, type RecordedResult } from "./a11y-report.ts";

/**
 * Workers run in parallel and each one writes the result of its combination to its own file; this joins
 * them into reports/a11y/report.json. It runs after a red run too, which is what the CI artifact is for.
 */
export default function globalTeardown(): void {
  if (!existsSync(A11Y_RESULTS_DIR)) return;
  const results = readdirSync(A11Y_RESULTS_DIR)
    .filter((name) => name.endsWith(".json"))
    .sort()
    .map((name) => JSON.parse(readFileSync(join(A11Y_RESULTS_DIR, name), "utf8")) as RecordedResult);
  writeFileSync(A11Y_REPORT_FILE, `${JSON.stringify(summarizeReport(results), null, 2)}\n`);
}
