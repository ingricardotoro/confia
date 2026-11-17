/**
 * The addresses of the chart of accounts and of the map of posting accounts. They live in `shared` because two areas name
 * them: the ledger, which owns the screens, and the charges, whose accrual page links to the map when it is incomplete.
 * A feature never imports another, so the path is the one thing both may share.
 */
export const LEDGER_PATH = "/ledger";

export const LEDGER_PATHS = {
  accounts: `${LEDGER_PATH}/accounts`,
  postingMap: `${LEDGER_PATH}/posting-accounts`,
} as const;
