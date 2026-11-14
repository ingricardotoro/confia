import { DEFAULT_ADMIN_LANGUAGE } from "../../i18n/resources.ts";

/**
 * A percentage of financial responsibility as the server delivers it (a number that already has two decimals), written
 * for the person with `Intl.NumberFormat` and always with two decimals: `60` is `60.00`. It only formats: it does not
 * round, add or compare, and the percent sign is the catalog's, not part of the number.
 */
export function formatPercent(value: number, locale: string = DEFAULT_ADMIN_LANGUAGE): string {
  return new Intl.NumberFormat(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2, useGrouping: false }).format(value);
}
