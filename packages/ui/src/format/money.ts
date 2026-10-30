/**
 * The only place an amount becomes display text (ADR-0011, CLAUDE.md rules 1 and 3). The amount
 * stays a string from the API to `Intl.NumberFormat`: nothing here converts it to a number, rounds
 * it or computes with it. The server already rounded it to the currency's minor unit.
 */

/** Shape of the API money DTO: `{ amount: string, currency: string }`. */
export interface MoneyValue {
  readonly amount: string;
  readonly currency: string;
}

export type MoneySign = "auto" | "always" | "accounting";

export interface MoneyFormatOptions {
  /**
   * `auto` shows a minus for negatives; `always` also shows a plus for positives (never for zero);
   * `accounting` wraps negatives in parentheses.
   */
  readonly sign: MoneySign;
  /** Shows the number without the currency symbol, for columns whose header names the currency. */
  readonly hideCurrency: boolean;
}

export type FormattedMoney =
  | { readonly kind: "formatted"; readonly visual: string; readonly spoken: string }
  | { readonly kind: "invalid" };

const INVALID: FormattedMoney = { kind: "invalid" };

// Optional minus, digits, and at most two significant decimals: extra decimals must be zeros
// ("3200.0000" is fine, "3200.125" would need rounding and is rejected).
const AMOUNT_PATTERN = /^-?\d+(?:\.\d{1,2}0*)?$/;
const ZERO_PATTERN = /^-?[0.]+$/;
const CURRENCY_PATTERN = /^[A-Z]{3}$/;

/** `Intl.NumberFormat.prototype.format` accepts a numeric string literal and reads it as an exact decimal. */
function isDecimalLiteral(text: string): text is `${number}` {
  return AMOUNT_PATTERN.test(text);
}

let knownCurrencies: ReadonlySet<string> | undefined;

function isKnownCurrency(code: string): boolean {
  knownCurrencies ??= new Set(Intl.supportedValuesOf("currency"));
  return CURRENCY_PATTERN.test(code) && knownCurrencies.has(code);
}

const formatters = new Map<string, Intl.NumberFormat>();

function formatterFor(
  locale: string,
  currency: string,
  mode: "currency" | "name" | "plain",
  signDisplay: "negative" | "exceptZero" | "never",
): Intl.NumberFormat {
  const cacheKey = [locale, currency, mode, signDisplay].join("|");
  let formatter = formatters.get(cacheKey);
  if (formatter === undefined) {
    formatter = new Intl.NumberFormat(locale, {
      ...(mode === "plain"
        ? { style: "decimal" }
        : { style: "currency", currency, currencyDisplay: mode === "name" ? "name" : "symbol" }),
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
      signDisplay,
    });
    formatters.set(cacheKey, formatter);
  }
  return formatter;
}

/**
 * Formats a money DTO for `locale`. Returns `invalid` for anything outside the contract (not a
 * string, not a decimal, more than two significant decimals, malformed or unknown currency, bad
 * locale) so the caller shows the "amount unavailable" text instead of a guessed figure. Never throws.
 */
export function formatMoney(value: MoneyValue, locale: string, options: MoneyFormatOptions): FormattedMoney {
  // The type says MoneyValue, but the data crossed a network boundary: check what the type promises.
  const candidate: unknown = value;
  if (typeof candidate !== "object" || candidate === null) return INVALID;
  const { amount, currency } = candidate as Partial<Record<keyof MoneyValue, unknown>>;
  if (typeof amount !== "string" || typeof currency !== "string") return INVALID;
  if (!isDecimalLiteral(amount) || !isKnownCurrency(currency)) return INVALID;

  // Zero is never shown negative or in parentheses: "-0.00" reads as 0.00.
  const parenthesise = options.sign === "accounting" && amount.startsWith("-") && !ZERO_PATTERN.test(amount);
  const signDisplay = options.sign === "always" ? "exceptZero" : "negative";
  const visualMode = options.hideCurrency ? "plain" : "currency";

  try {
    // Intl ignores currencySign "accounting" in es-HN (probe S3), so the parentheses are added here
    // around the formatted absolute value. This is string handling only: no arithmetic.
    const visual = parenthesise
      ? `(${formatterFor(locale, currency, visualMode, "never").format(amount)})`
      : formatterFor(locale, currency, visualMode, signDisplay).format(amount);
    const spoken = formatterFor(locale, currency, "name", signDisplay).format(amount);
    return { kind: "formatted", visual, spoken };
  } catch {
    return INVALID;
  }
}
