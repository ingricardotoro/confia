import { DEFAULT_ADMIN_LANGUAGE } from "../../i18n/resources.ts";

/**
 * A date with no time, as the server sends it (`YYYY-MM-DD`), written for the person with `Intl.DateTimeFormat`.
 * It has no zone, so it is formatted as UTC: in any other zone the day could move.
 */
export function formatIsoDate(isoDate: string, locale: string = DEFAULT_ADMIN_LANGUAGE): string {
  const [year = 0, month = 1, day = 1] = isoDate.split("-").map(Number);
  return new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeZone: "UTC" }).format(new Date(Date.UTC(year, month - 1, day)));
}
