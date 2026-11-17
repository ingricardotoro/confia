import { DEFAULT_ADMIN_LANGUAGE } from "../../i18n/resources.ts";

/**
 * A percentage of a tax rate as the server delivers it: a text with exactly four decimals (`"15.0000"`). It is handed
 * to `Intl.NumberFormat` as the text it is, which reads it as an exact decimal, so it is never turned into a `number`,
 * rounded, added or compared. It keeps the four decimals and the percent sign is the catalog's, not part of the number.
 */
export function formatRatePercent(rate: string, locale: string = DEFAULT_ADMIN_LANGUAGE): string {
  const formatter = new Intl.NumberFormat(locale, { minimumFractionDigits: 4, maximumFractionDigits: 4, useGrouping: false });
  // The text is read as an exact decimal; the cast only tells the compiler it has the shape of a numeric string.
  return formatter.format(rate as `${number}`);
}
