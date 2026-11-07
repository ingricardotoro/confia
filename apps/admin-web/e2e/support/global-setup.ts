import { mkdirSync, rmSync } from "node:fs";
import { A11Y_REPORT_DIR, A11Y_RESULTS_DIR } from "./a11y-paths.ts";

/** Starts every run with an empty report folder, so a report never carries a result of an earlier run. */
export default function globalSetup(): void {
  rmSync(A11Y_REPORT_DIR, { recursive: true, force: true });
  mkdirSync(A11Y_RESULTS_DIR, { recursive: true });
}
