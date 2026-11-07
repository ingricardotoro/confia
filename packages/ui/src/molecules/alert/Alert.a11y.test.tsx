import { afterEach, describe, expect, it } from "vitest";
import { createRoot, type Root } from "react-dom/client";
import type { ReactElement } from "react";
import { I18nextProvider } from "react-i18next";
import "../../tokens/index.css";
import { createUiI18n } from "../../i18n/create-ui-i18n";
import { Alert } from "./Alert";

// What a class string cannot prove in jsdom: that the --alert-* variables resolve, in Chromium and in
// both themes, to the status tokens that have a verified contrast (design.md D13, DS82).
const TONE = { info: "pending", warning: "partial", destructive: "overdue" } as const;
const SENTINEL = "rebeccapurple";

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

// An unresolved token would inherit the sentinel, and the guard rejects that.
function tokenColor(dom: HTMLElement, token: string): string {
  const outer = document.createElement("div");
  outer.style.color = SENTINEL;
  const probe = document.createElement("span");
  probe.style.color = `var(${token})`;
  outer.append(probe);
  dom.append(outer);
  const color = getComputedStyle(probe).color;
  const sentinel = document.createElement("span");
  sentinel.style.color = SENTINEL;
  dom.append(sentinel);
  expect(color, token).not.toBe(getComputedStyle(sentinel).color);
  return color;
}

describe.each(["light", "dark"] as const)("Alert in Chromium, %s theme", (theme) => {
  it.each(Object.entries(TONE))("DS82: %s takes its three colors from the status tokens", async (variant, tone) => {
    document.documentElement.setAttribute("data-theme", theme);
    const dom = await mount(<Alert variant={variant as keyof typeof TONE}>Contenido</Alert>);
    const style = getComputedStyle(dom.firstElementChild as HTMLElement);

    expect(style.backgroundColor).toBe(tokenColor(dom, `--color-status-${tone}-bg`));
    expect(style.color).toBe(tokenColor(dom, `--color-status-${tone}-fg`));
    expect(style.borderTopColor).toBe(tokenColor(dom, `--color-status-${tone}-border`));
  });

  it("DS82: the three variants have three different backgrounds", async () => {
    document.documentElement.setAttribute("data-theme", theme);
    const dom = await mount(
      <>
        <Alert variant="info">Uno</Alert>
        <Alert variant="warning">Dos</Alert>
        <Alert variant="destructive">Tres</Alert>
      </>,
    );
    const backgrounds = [...dom.children].map((alert) => getComputedStyle(alert).backgroundColor);

    expect(new Set(backgrounds).size).toBe(3);
  });
});

describe("Alert icons in Chromium", () => {
  it("DS82: each variant draws a decorative icon with a size, and no two share a drawing", async () => {
    const shapes: (string | undefined)[] = [];
    for (const variant of ["info", "warning", "destructive"] as const) {
      const dom = await mount(<Alert variant={variant}>Contenido</Alert>);
      const icon = dom.getElementsByTagName("svg")[0];

      expect(icon?.getAttribute("aria-hidden"), variant).toBe("true");
      expect(icon?.getBoundingClientRect().width, variant).toBeGreaterThan(0);
      shapes.push(icon?.innerHTML);
      root?.unmount();
      host?.remove();
    }

    expect(new Set(shapes).size).toBe(3);
  });
});
