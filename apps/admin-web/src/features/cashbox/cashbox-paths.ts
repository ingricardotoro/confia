import { MY_CASHBOX_PATH } from "../../shared/navigation/cashbox-paths.ts";

/** The path of the cashbox area. It has the cashier's own session (the area's address) and the list of sessions below it. */
export const CASHBOX_PATH = MY_CASHBOX_PATH;

export const CASHBOX_PATHS = {
  mine: CASHBOX_PATH,
  sessions: `${CASHBOX_PATH}/sessions`,
} as const;

/** The address of one session and its closing report. The id is encoded, so it can never change the path. */
export const cashSessionPath = (id: string): string => `${CASHBOX_PATHS.sessions}/${encodeURIComponent(id)}`;
