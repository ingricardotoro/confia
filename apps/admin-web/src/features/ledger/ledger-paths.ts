import type { AdminKey } from "../../i18n/resources.ts";
import { LEDGER_PATH, LEDGER_PATHS } from "../../shared/navigation/ledger-paths.ts";

export { LEDGER_PATH, LEDGER_PATHS };

export interface LedgerView {
  readonly id: "accounts" | "posting-map";
  readonly path: (typeof LEDGER_PATHS)[keyof typeof LEDGER_PATHS];
  readonly labelKey: AdminKey;
}

/** The views of the area, in the order of its sub navigation. */
export const LEDGER_VIEWS: readonly LedgerView[] = [
  { id: "accounts", path: LEDGER_PATHS.accounts, labelKey: "ledger.tabs.accounts" },
  { id: "posting-map", path: LEDGER_PATHS.postingMap, labelKey: "ledger.tabs.postingMap" },
];

/** Whether `view` is the one being shown: its path, or a path below it. */
export function isCurrentLedgerView(view: LedgerView, pathname: string): boolean {
  const current = pathname.replace(/\/+$/, "") || "/";
  return current === view.path || current.startsWith(`${view.path}/`);
}
