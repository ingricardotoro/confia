// The one table of money formatting cases. money.test.ts checks it against formatMoney and, from
// task 6.1, the MoneyAmount tests check the same rows through the component, so the two cannot
// drift apart (design.md decision 12). Expected strings are the literal es-HN output recorded in
// probe S3: the space after the symbol is U+00A0, and USD renders as "USD", not "US$".

export type MoneySignMode = "auto" | "always" | "accounting";

export interface ValidMoneyCase {
  /** Scenario id from specs/design-system/spec.md. */
  readonly scenario: string;
  readonly amount: string;
  readonly currency: string;
  readonly sign: MoneySignMode;
  readonly hideCurrency: boolean;
  /** Literal visual text for the es-HN locale. */
  readonly visual: string;
}

export interface InvalidMoneyCase {
  readonly scenario: string;
  /** Deliberately loose: several rows evade the types to model malformed runtime data. */
  readonly amount: unknown;
  readonly currency: unknown;
}

const NBSP = " ";

function valid(
  scenario: string,
  amount: string,
  currency: string,
  visual: string,
  options: { sign?: MoneySignMode; hideCurrency?: boolean } = {},
): ValidMoneyCase {
  return {
    scenario,
    amount,
    currency,
    sign: options.sign ?? "auto",
    hideCurrency: options.hideCurrency ?? false,
    visual,
  };
}

export const validMoneyCases: readonly ValidMoneyCase[] = [
  valid("DS29", "3200.00", "HNL", `L${NBSP}3,200.00`),
  valid("DS30", "3200.00", "USD", `USD${NBSP}3,200.00`),
  valid("DS31", "0.00", "HNL", `L${NBSP}0.00`),
  valid("DS32", "-0.00", "HNL", `L${NBSP}0.00`),
  valid("DS32", "-0.0000", "HNL", `L${NBSP}0.00`),
  valid("DS33", "-1500.00", "HNL", `-L${NBSP}1,500.00`),
  valid("DS34", "-1500.00", "HNL", `(L${NBSP}1,500.00)`, { sign: "accounting" }),
  valid("DS34", "1500.00", "HNL", `L${NBSP}1,500.00`, { sign: "accounting" }),
  valid("DS34", "-0.00", "HNL", `L${NBSP}0.00`, { sign: "accounting" }),
  valid("DS34", "-1500.00", "HNL", "(1,500.00)", { sign: "accounting", hideCurrency: true }),
  valid("DS35", "9999999999.99", "HNL", `L${NBSP}9,999,999,999.99`),
  valid("DS35", "-9999999999.99", "HNL", `-L${NBSP}9,999,999,999.99`),
  valid("DS36", "123456789012345678.90", "HNL", `L${NBSP}123,456,789,012,345,678.90`),
  valid("DS37", "3200.0000", "HNL", `L${NBSP}3,200.00`),
  valid("DS37", "3200.1000", "HNL", `L${NBSP}3,200.10`),
  valid("DS37", "3200.500", "HNL", `L${NBSP}3,200.50`),
  valid("DS38", "3200", "HNL", `L${NBSP}3,200.00`),
  valid("DS38", "3200.5", "HNL", `L${NBSP}3,200.50`),
  valid("DS39", "1500.00", "HNL", new Intl.NumberFormat("es-HN", { style: "currency", currency: "HNL" }).format("1500.00")),
  valid("DS51", "3200.00", "HNL", `+L${NBSP}3,200.00`, { sign: "always" }),
  valid("DS51", "0.00", "HNL", `L${NBSP}0.00`, { sign: "always" }),
  valid("DS51", "-0.00", "HNL", `L${NBSP}0.00`, { sign: "always" }),
  valid("DS51", "-1500.00", "HNL", `-L${NBSP}1,500.00`, { sign: "always" }),
  valid("hide-currency", "3200.00", "HNL", "3,200.00", { hideCurrency: true }),
  valid("hide-currency", "-1500.00", "HNL", "-1,500.00", { hideCurrency: true }),
];

export const invalidMoneyCases: readonly InvalidMoneyCase[] = [
  { scenario: "DS43", amount: "3200.005", currency: "HNL" },
  { scenario: "DS43", amount: "3200.125", currency: "HNL" },
  { scenario: "DS43", amount: "-3200.001", currency: "HNL" },
  ...["abc", "", " 12.00", "1e3", "1,500.00", "+5.00", "12.", ".5", "--1.00", "12.00 ", "١٢.٠٠"].map(
    (amount) => ({ scenario: "DS44", amount, currency: "HNL" }),
  ),
  { scenario: "DS45", amount: 1500, currency: "HNL" },
  { scenario: "DS45", amount: null, currency: "HNL" },
  { scenario: "DS45", amount: undefined, currency: "HNL" },
  { scenario: "DS45", amount: 10n, currency: "HNL" },
  ...["hnl", "HN", "HNLL", "", "QQQ", " HNL"].map((currency) => ({
    scenario: "DS46",
    amount: "3200.00",
    currency,
  })),
  { scenario: "DS46", amount: "3200.00", currency: 840 },
  { scenario: "DS46", amount: "3200.00", currency: undefined },
];
