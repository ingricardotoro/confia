import { writeFileSync } from "node:fs";
import { join } from "node:path";
import { A11Y_RESULTS_DIR } from "./a11y-paths.ts";
import { toRecorded, type GateResult } from "./a11y-report.ts";

/** One file per evaluation, because workers run in parallel; global-teardown joins them into report.json. */
export function recordResult(result: GateResult): void {
  const name = `${result.route}-${result.state}-${result.theme}`.replace(/[^A-Za-z0-9]+/g, "-");
  writeFileSync(join(A11Y_RESULTS_DIR, `${name}.json`), JSON.stringify(toRecorded(result)));
}
