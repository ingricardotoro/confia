import { afterEach, describe, expect, it } from "vitest";
import { createRoot, type Root } from "react-dom/client";
import type { ReactElement } from "react";
import { I18nextProvider } from "react-i18next";
import "../../tokens/index.css";
import { validMoneyCases } from "../../format/__fixtures__/money-cases";
import { createUiI18n } from "../../i18n/create-ui-i18n";
import { MoneyAmount } from "./MoneyAmount";

// What needs the real browser: the compiled stylesheet (tabular numerals, sign colors) and the Chromium
// build of Intl, which must print the same text as Node 24 does for the rows recorded in probe S3.
let root: Root | undefined;
let host: HTMLElement | undefined;

async function mount(ui: ReactElement): Promise<HTMLElement> {
  host = document.createElement("div");
  document.body.append(host);
  root = createRoot(host);
  root.render(<I18nextProvider i18n={createUiI18n()}>{ui}</I18nextProvider>);
  await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
  return host;
}

afterEach(() => {
  root?.unmount();
  host?.remove();
});

function amountIn(dom: HTMLElement): HTMLElement {
  const element = dom.querySelector<HTMLElement>(".money-amount");
  if (element === null) throw new Error("no MoneyAmount rendered");
  return element;
}

function visualIn(dom: HTMLElement): string {
  const element = dom.querySelector("[aria-hidden='true']");
  if (element === null) throw new Error("no visual form rendered");
  return element.textContent;
}

function colorOfClass(dom: HTMLElement, className: string): string {
  const element = document.createElement("span");
  element.className = className;
  dom.append(element);
  return getComputedStyle(element).color;
}

describe("MoneyAmount in Chromium", () => {
  it("DS48: uses tabular numerals, for a valid and for an unavailable amount", async () => {
    const valid = await mount(<MoneyAmount value={{ amount: "3200.00", currency: "HNL" }} />);
    expect(getComputedStyle(amountIn(valid)).fontVariantNumeric).toContain("tabular-nums");
    root?.unmount();
    host?.remove();

    const invalid = await mount(<MoneyAmount value={{ amount: "abc", currency: "HNL" }} />);
    expect(getComputedStyle(amountIn(invalid)).fontVariantNumeric).toContain("tabular-nums");
  });

  it("DS48: the caller cannot turn tabular numerals off, by class or by inline style", async () => {
    const dom = await mount(
      <>
        <MoneyAmount value={{ amount: "1.00", currency: "HNL" }} className="normal-nums" />
        <MoneyAmount value={{ amount: "2.00", currency: "HNL" }} style={{ fontVariantNumeric: "normal" }} />
      </>,
    );

    for (const element of dom.querySelectorAll<HTMLElement>(".money-amount")) {
      expect(getComputedStyle(element).fontVariantNumeric).toContain("tabular-nums");
    }
  });

  it("DS50: tints by sign only when asked, with the semantic token", async () => {
    const dom = await mount(
      <>
        <span data-testid="plain">
          <MoneyAmount value={{ amount: "-50.00", currency: "HNL" }} />
        </span>
        <span data-testid="negative">
          <MoneyAmount value={{ amount: "-50.00", currency: "HNL" }} colorBySign />
        </span>
        <span data-testid="positive">
          <MoneyAmount value={{ amount: "50.00", currency: "HNL" }} colorBySign />
        </span>
      </>,
    );
    const colorIn = (id: string) => getComputedStyle(amountIn(dom.querySelector<HTMLElement>(`[data-testid="${id}"]`) ?? dom)).color;

    expect(colorIn("negative")).toBe(colorOfClass(dom, "text-status-overdue-fg"));
    expect(colorIn("positive")).toBe(colorOfClass(dom, "text-status-paid-fg"));
    expect(colorIn("plain")).not.toBe(colorIn("negative"));
    expect(colorIn("plain")).toBe(getComputedStyle(dom).color);
  });

  it("DS39: prints in Chromium the same text as Node 24 for every recorded case", async () => {
    for (const { amount, currency, sign, hideCurrency, visual } of validMoneyCases) {
      const dom = await mount(
        <MoneyAmount
          value={{ amount, currency }}
          accounting={sign === "accounting"}
          showPositiveSign={sign === "always"}
          hideCurrency={hideCurrency}
        />,
      );

      expect(visualIn(dom), `${amount} ${currency} ${sign}`).toBe(visual);
      root?.unmount();
      host?.remove();
    }
  });
});
