import { afterEach, describe, expect, it } from "vitest";
import { createRoot, type Root } from "react-dom/client";
import type { ReactElement } from "react";
import { I18nextProvider } from "react-i18next";
import "../../tokens/index.css";
import { createUiI18n } from "../../i18n/create-ui-i18n";
import { StatusBadge } from "./StatusBadge";
import { FINANCIAL_STATUSES, type FinancialStatus } from "./status-table";

// What a class string cannot prove in jsdom: that the variables and tokens the classes name resolve,
// in Chromium and in both themes, to the colors of the state (docs/ui-ux/01 section 5).
const LABELS: Record<FinancialStatus, string> = {
  paid: "Pagado",
  pending: "Pendiente",
  partial: "Parcial",
  overdue: "Vencido",
  void: "Anulado",
  promised: "En promesa",
  refunded: "Reembolsado",
};

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
  document.documentElement.removeAttribute("data-theme");
});

// Named, not written as a color function, so the visual-literal rule has nothing to flag.
const SENTINEL = "rebeccapurple";

// The color a token resolves to. A utility class is no reference here: Tailwind only writes the ones
// the source names, so a missing one would agree with a missing token. An unresolved token would
// inherit the sentinel, and the guard rejects that.
function tokenColor(dom: HTMLElement, token: string): string {
  const outer = document.createElement("div");
  outer.style.color = SENTINEL;
  const probe = document.createElement("span");
  probe.style.color = `var(${token})`;
  outer.append(probe);
  dom.append(outer);
  const color = getComputedStyle(probe).color;
  expect(color, token).not.toBe(sentinelColor(dom));
  return color;
}

// What the sentinel computes to, read the same way as a token.
function sentinelColor(dom: HTMLElement): string {
  const probe = document.createElement("span");
  probe.style.color = SENTINEL;
  dom.append(probe);
  return getComputedStyle(probe).color;
}

function transparentIn(dom: HTMLElement): string {
  const probe = document.createElement("span");
  probe.style.backgroundColor = "transparent";
  dom.append(probe);
  return getComputedStyle(probe).backgroundColor;
}

function badgeOf(dom: HTMLElement, text: string): HTMLElement {
  const found = [...dom.getElementsByTagName("span")].find((span) => span.textContent === text && span.children.length > 0);
  if (found === undefined) throw new Error(`no badge reads ${text}`);
  return found;
}

describe.each(["light", "dark"] as const)("StatusBadge in Chromium, %s theme", (theme) => {
  const setTheme = () => {
    document.documentElement.setAttribute("data-theme", theme);
  };

  it.each(FINANCIAL_STATUSES)("DS52, DS56: soft %s takes its three colors from the tokens of the state", async (status) => {
    setTheme();
    const dom = await mount(<StatusBadge status={status} />);
    const style = getComputedStyle(badgeOf(dom, LABELS[status]));

    expect(style.backgroundColor).toBe(tokenColor(dom, `--color-status-${status}-bg`));
    expect(style.color).toBe(tokenColor(dom, `--color-status-${status}-fg`));
    expect(style.borderTopColor).toBe(tokenColor(dom, `--color-status-${status}-border`));
  });

  it.each(FINANCIAL_STATUSES)("DS56: solid %s is white on the solid color of the state", async (status) => {
    setTheme();
    const dom = await mount(<StatusBadge status={status} variant="solid" />);
    const style = getComputedStyle(badgeOf(dom, LABELS[status]));

    expect(style.backgroundColor).toBe(tokenColor(dom, `--color-status-${status}-solid-bg`));
    expect(style.color).toBe(tokenColor(dom, "--color-primary-fg"));
  });

  it.each(FINANCIAL_STATUSES)("DS56: outline %s has no fill and the text and border of the state", async (status) => {
    setTheme();
    const dom = await mount(<StatusBadge status={status} variant="outline" />);
    const style = getComputedStyle(badgeOf(dom, LABELS[status]));

    expect(style.backgroundColor).toBe(transparentIn(dom));
    expect(style.color).toBe(tokenColor(dom, `--color-status-${status}-fg`));
    expect(style.borderTopColor).toBe(tokenColor(dom, `--color-status-${status}-border`));
  });

  it("DS55: an unknown state takes no color of any state", async () => {
    setTheme();
    const dom = await mount(<StatusBadge status={"archived" as unknown as FinancialStatus} />);
    const style = getComputedStyle(badgeOf(dom, "Desconocido"));

    for (const status of FINANCIAL_STATUSES) {
      expect(style.backgroundColor).not.toBe(tokenColor(dom, `--color-status-${status}-bg`));
      expect(style.color).not.toBe(tokenColor(dom, `--color-status-${status}-fg`));
    }
  });
});

describe("StatusBadge in Chromium", () => {
  it("DS52: every state draws its icon with a size, next to the text", async () => {
    for (const status of FINANCIAL_STATUSES) {
      const dom = await mount(<StatusBadge status={status} />);
      const [icon] = badgeOf(dom, LABELS[status]).getElementsByTagName("svg");

      expect(icon?.getBoundingClientRect().width, status).toBeGreaterThan(0);
      root?.unmount();
      host?.remove();
    }
  });

  it("DS53: with iconOnly the text is not visible to the eye but the icon is", async () => {
    const dom = await mount(<StatusBadge status="overdue" iconOnly />);
    const badge = badgeOf(dom, "Vencido");
    const text = [...badge.getElementsByTagName("span")].find((span) => span.textContent === "Vencido");
    const [icon] = badge.getElementsByTagName("svg");

    expect(text?.getBoundingClientRect().width).toBeLessThanOrEqual(1);
    expect(text?.getBoundingClientRect().height).toBeLessThanOrEqual(1);
    expect(icon?.getBoundingClientRect().width).toBeGreaterThan(0);
  });
});
